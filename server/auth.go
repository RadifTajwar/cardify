package main

import (
	"cmp"
	"context"
	"crypto/rand"
	"crypto/sha256"
	"crypto/subtle"
	"encoding/hex"
	"errors"
	"fmt"
	"log"
	"math/big"
	"net/http"
	"net/mail"
	"net/url"
	"strings"
	"time"
	"unicode/utf8"

	"go.mongodb.org/mongo-driver/v2/bson"
	"go.mongodb.org/mongo-driver/v2/mongo"
	"go.mongodb.org/mongo-driver/v2/mongo/options"
	"golang.org/x/crypto/bcrypt"
)

const (
	sessionTTL  = 180 * 24 * time.Hour
	maxFailures = 10 // wrong passwords for one email before its logins pause
	lockout     = 15 * time.Minute
	resetTTL    = 15 * time.Minute // how long an emailed password reset code works
	maxMisses   = 10               // wrong reset codes for one email in a day, so 6 digits can't be guessed
)

// A session is one login. Its id is the hash of the token the app holds, so a database leak doesn't leak logins.
type session struct {
	ID        string    `bson:"_id"`
	UserID    string    `bson:"userId"`
	ExpiresAt time.Time `bson:"expiresAt"`
}

// A passwordReset holds the code emailed to someone who forgot their password. It's keyed by email, so asking
// again replaces the code while wrong guesses keep counting until the record is purged.
type passwordReset struct {
	CodeHash  string    `bson:"codeHash"`
	Misses    int       `bson:"misses"`
	ExpiresAt time.Time `bson:"expiresAt"`
	PurgeAt   time.Time `bson:"purgeAt"` // a day after the first code; MongoDB then deletes the record, misses and all
}

// credentials is the body of every /api/auth call; each reads the fields it needs.
type credentials struct {
	Name     string `json:"name"`
	Email    string `json:"email"`
	Password string `json:"password"`
	Code     string `json:"code"`    // password reset
	IDToken  string `json:"idToken"` // Google sign-in
}

// authed runs h for a logged-in caller, passing their user id; anyone else gets a 401.
func (s *store) authed(h func(w http.ResponseWriter, r *http.Request, uid string)) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		var sess session
		err := s.sessions.FindOne(r.Context(), bson.M{"_id": hashToken(bearer(r)), "expiresAt": bson.M{"$gt": time.Now()}}).Decode(&sess)
		switch {
		case errors.Is(err, mongo.ErrNoDocuments):
			http.Error(w, "please log in again", http.StatusUnauthorized)
		case err != nil:
			serverError(w, err)
		default:
			h(w, r, sess.UserID)
		}
	}
}

func (s *store) signup(w http.ResponseWriter, r *http.Request) {
	in, ok := decodeCredentials(w, r)
	if !ok {
		return
	}
	if addr, err := mail.ParseAddress(in.Email); err != nil || addr.Address != in.Email {
		http.Error(w, "enter a valid email address", http.StatusBadRequest)
		return
	}
	if in.Name == "" {
		http.Error(w, "enter your name", http.StatusBadRequest)
		return
	}
	hash, ok := hashPassword(w, in.Password)
	if !ok {
		return
	}
	u := User{ID: rand.Text(), Name: in.Name, Email: in.Email, PasswordHash: hash, CreatedAt: now()}
	_, err := s.users.InsertOne(r.Context(), u)
	if mongo.IsDuplicateKeyError(err) {
		http.Error(w, "an account with this email already exists; log in instead", http.StatusConflict)
		return
	}
	if err != nil {
		serverError(w, err)
		return
	}
	s.startSession(w, r, u, http.StatusCreated)
}

func (s *store) login(w http.ResponseWriter, r *http.Request) {
	in, ok := decodeCredentials(w, r)
	if !ok {
		return
	}
	ctx := r.Context()
	var failed struct {
		Count int `bson:"count"`
	}
	err := s.failures.FindOne(ctx, bson.M{"_id": in.Email, "expiresAt": bson.M{"$gt": time.Now()}}).Decode(&failed)
	if err != nil && !errors.Is(err, mongo.ErrNoDocuments) {
		serverError(w, err)
		return
	}
	if failed.Count >= maxFailures {
		http.Error(w, "too many wrong passwords; try again in 15 minutes", http.StatusTooManyRequests)
		return
	}

	var u User
	err = s.users.FindOne(ctx, bson.M{"email": in.Email}).Decode(&u)
	if err != nil && !errors.Is(err, mongo.ErrNoDocuments) {
		serverError(w, err)
		return
	}
	if err == nil && len(u.PasswordHash) == 0 {
		http.Error(w, "this account signs in with Google; tap Continue with Google, or reset your password to set one", http.StatusUnauthorized)
		return
	}
	if err != nil || bcrypt.CompareHashAndPassword(u.PasswordHash, []byte(in.Password)) != nil {
		// Count the miss; the first one starts the 15-minute window.
		_, err := s.failures.UpdateOne(ctx, bson.M{"_id": in.Email},
			bson.M{"$inc": bson.M{"count": 1}, "$setOnInsert": bson.M{"expiresAt": time.Now().Add(lockout)}},
			options.UpdateOne().SetUpsert(true))
		if err != nil {
			log.Print(err)
		}
		http.Error(w, "wrong email or password", http.StatusUnauthorized)
		return
	}
	if _, err := s.failures.DeleteOne(ctx, bson.M{"_id": in.Email}); err != nil {
		log.Print(err)
	}
	s.startSession(w, r, u, http.StatusOK)
}

// google signs someone in with an ID token from Sign in with Google, making their account on first use.
// An account that already has the email Google vouches for becomes theirs.
func (s *store) google(w http.ResponseWriter, r *http.Request) {
	if s.googleClientID == "" {
		http.Error(w, "Google sign-in isn't set up on the server yet (GOOGLE_CLIENT_ID)", http.StatusServiceUnavailable)
		return
	}
	in, ok := decodeCredentials(w, r)
	if !ok {
		return
	}
	g, err := s.googleUser(r.Context(), in.IDToken)
	if err != nil {
		log.Print(err)
		http.Error(w, "Google sign-in didn't work; please try again", http.StatusUnauthorized)
		return
	}
	ctx, status := r.Context(), http.StatusOK
	var u User
	err = s.users.FindOne(ctx, bson.M{"googleId": g.Sub}).Decode(&u)
	if errors.Is(err, mongo.ErrNoDocuments) {
		err = s.users.FindOneAndUpdate(ctx, bson.M{"email": g.Email}, bson.M{"$set": bson.M{"googleId": g.Sub}},
			options.FindOneAndUpdate().SetReturnDocument(options.After)).Decode(&u)
	}
	if errors.Is(err, mongo.ErrNoDocuments) {
		u = User{ID: rand.Text(), Name: cmp.Or(g.Name, strings.Split(g.Email, "@")[0]), Email: g.Email, GoogleID: g.Sub, CreatedAt: now()}
		_, err = s.users.InsertOne(ctx, u)
		status = http.StatusCreated
	}
	if err != nil {
		serverError(w, err)
		return
	}
	s.startSession(w, r, u, status)
}

// googleIdentity is what Google's tokeninfo says about an ID token.
type googleIdentity struct {
	Aud           string `json:"aud"`
	Iss           string `json:"iss"`
	Sub           string `json:"sub"`
	Email         string `json:"email"`
	EmailVerified string `json:"email_verified"`
	Name          string `json:"name"`
}

// googleUser asks Google whose ID token this is (tokeninfo checks its signature and expiry) and checks it was
// issued to Cardify for a verified email.
// ponytail: one call to Google per sign-in; verify the token locally against Google's keys if sign-ins get frequent.
func (s *store) googleUser(ctx context.Context, idToken string) (googleIdentity, error) {
	var g googleIdentity
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, s.tokenInfoURL+"?id_token="+url.QueryEscape(idToken), nil)
	if err != nil {
		return g, err
	}
	if err := doJSON(req, &g); err != nil {
		return g, err
	}
	g.Email = strings.ToLower(g.Email)
	switch {
	case g.Aud != s.googleClientID:
		return g, fmt.Errorf("google token issued to another app: %s", g.Aud)
	case g.Iss != "accounts.google.com" && g.Iss != "https://accounts.google.com":
		return g, fmt.Errorf("google token issued by %q", g.Iss)
	case g.Sub == "" || g.Email == "" || g.EmailVerified != "true":
		return g, errors.New("google token without a verified email")
	}
	return g, nil
}

// forgot emails a 6-digit code for choosing a new password. It answers the same whether or not the email has an
// account, so nobody can use it to find out who does.
func (s *store) forgot(w http.ResponseWriter, r *http.Request) {
	if s.sendMail == nil {
		http.Error(w, "password reset isn't set up on the server yet", http.StatusServiceUnavailable)
		return
	}
	in, ok := decodeCredentials(w, r)
	if !ok {
		return
	}
	ctx := r.Context()
	var u User
	err := s.users.FindOne(ctx, bson.M{"email": in.Email}).Decode(&u)
	if errors.Is(err, mongo.ErrNoDocuments) {
		w.WriteHeader(http.StatusNoContent)
		return
	}
	if err != nil {
		serverError(w, err)
		return
	}
	// At most one email a minute per address, so nobody can flood an inbox.
	recent, err := s.resets.CountDocuments(ctx, bson.M{"_id": u.Email, "sentAt": bson.M{"$gt": time.Now().Add(-time.Minute)}})
	if err != nil {
		serverError(w, err)
		return
	}
	if recent > 0 {
		w.WriteHeader(http.StatusNoContent)
		return
	}
	n, _ := rand.Int(rand.Reader, big.NewInt(1_000_000))
	code, t := fmt.Sprintf("%06d", n), time.Now()
	_, err = s.resets.UpdateOne(ctx, bson.M{"_id": u.Email}, bson.M{
		"$set":         bson.M{"codeHash": hashToken(u.Email + code), "sentAt": t, "expiresAt": t.Add(resetTTL)},
		"$setOnInsert": bson.M{"misses": 0, "purgeAt": t.Add(24 * time.Hour)},
	}, options.UpdateOne().SetUpsert(true))
	if err != nil {
		serverError(w, err)
		return
	}
	body := fmt.Sprintf("Hi %s,\n\nYour Cardify code is %s. Enter it in the app to choose a new password; it works for 15 minutes.\n\n"+
		"If you didn't ask for it, ignore this email and your password stays the same.\n", u.Name, code)
	if err := s.sendMail(ctx, u.Email, "Your Cardify code: "+code, body); err != nil {
		serverError(w, err)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

// reset sets a new password with the emailed code, logs the account out everywhere, and logs this device in.
func (s *store) reset(w http.ResponseWriter, r *http.Request) {
	in, ok := decodeCredentials(w, r)
	if !ok {
		return
	}
	ctx := r.Context()
	var pr passwordReset
	err := s.resets.FindOne(ctx, bson.M{"_id": in.Email}).Decode(&pr)
	if err != nil && !errors.Is(err, mongo.ErrNoDocuments) {
		serverError(w, err)
		return
	}
	switch {
	case pr.Misses >= maxMisses:
		http.Error(w, "too many wrong codes; try again tomorrow", http.StatusTooManyRequests)
		return
	case err != nil || time.Now().After(pr.ExpiresAt):
		http.Error(w, "that code has expired; ask for a new one", http.StatusBadRequest)
		return
	case subtle.ConstantTimeCompare([]byte(hashToken(in.Email+strings.TrimSpace(in.Code))), []byte(pr.CodeHash)) != 1:
		if _, err := s.resets.UpdateOne(ctx, bson.M{"_id": in.Email}, bson.M{"$inc": bson.M{"misses": 1}}); err != nil {
			log.Print(err)
		}
		http.Error(w, "that code isn't right", http.StatusBadRequest)
		return
	}
	hash, ok := hashPassword(w, in.Password)
	if !ok {
		return
	}
	var u User
	if err := s.users.FindOneAndUpdate(ctx, bson.M{"email": in.Email}, bson.M{"$set": bson.M{"passwordHash": hash}},
		options.FindOneAndUpdate().SetReturnDocument(options.After)).Decode(&u); err != nil {
		serverError(w, err)
		return
	}
	// The code is used up, and whoever knew the old password is logged out.
	for c, filter := range map[*mongo.Collection]bson.M{s.resets: {"_id": u.Email}, s.failures: {"_id": u.Email}, s.sessions: {"userId": u.ID}} {
		if _, err := c.DeleteMany(ctx, filter); err != nil {
			log.Print(err)
		}
	}
	s.startSession(w, r, u, http.StatusOK)
}

// startSession logs u in: it stores a new session and answers with the token the app keeps.
func (s *store) startSession(w http.ResponseWriter, r *http.Request, u User, status int) {
	token := rand.Text()
	if _, err := s.sessions.InsertOne(r.Context(), session{hashToken(token), u.ID, time.Now().Add(sessionTTL)}); err != nil {
		serverError(w, err)
		return
	}
	writeJSON(w, status, map[string]any{"token": token, "user": u})
}

func (s *store) logout(w http.ResponseWriter, r *http.Request, _ string) {
	if _, err := s.sessions.DeleteOne(r.Context(), bson.M{"_id": hashToken(bearer(r))}); err != nil {
		serverError(w, err)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

// hashPassword checks a new password and hashes it; when it's unusable it has already answered.
func hashPassword(w http.ResponseWriter, password string) ([]byte, bool) {
	if utf8.RuneCountInString(password) < 8 {
		http.Error(w, "use a password of at least 8 characters", http.StatusBadRequest)
		return nil, false
	}
	hash, err := bcrypt.GenerateFromPassword([]byte(password), bcrypt.DefaultCost)
	if errors.Is(err, bcrypt.ErrPasswordTooLong) {
		http.Error(w, "that password is too long (72 characters at most)", http.StatusBadRequest)
		return nil, false
	}
	if err != nil {
		serverError(w, err)
		return nil, false
	}
	return hash, true
}

func decodeCredentials(w http.ResponseWriter, r *http.Request) (credentials, bool) {
	var c credentials
	ok := decodeJSON(w, r, &c)
	c.Name, c.Email = strings.TrimSpace(c.Name), strings.ToLower(strings.TrimSpace(c.Email))
	return c, ok
}

func bearer(r *http.Request) string {
	token, _ := strings.CutPrefix(r.Header.Get("Authorization"), "Bearer ")
	return token
}

func hashToken(token string) string {
	sum := sha256.Sum256([]byte(token))
	return hex.EncodeToString(sum[:])
}
