// Command server is the Cardify API: accounts and their visiting cards, stored in MongoDB, with card photos on Cloudinary.
package main

import (
	"cmp"
	"context"
	"crypto/rand"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"io"
	"log"
	"net/http"
	"net/mail"
	"os"
	"regexp"
	"strings"
	"time"
	"unicode/utf8"

	"go.mongodb.org/mongo-driver/v2/bson"
	"go.mongodb.org/mongo-driver/v2/mongo"
	"go.mongodb.org/mongo-driver/v2/mongo/options"
	"golang.org/x/crypto/bcrypt"
)

// CardFields are the parts of a card its owner edits; the rest of a Card is server-owned.
type CardFields struct {
	Name    string `bson:"name" json:"name"`
	Title   string `bson:"title" json:"title"`
	Company string `bson:"company" json:"company"`
	Phone   string `bson:"phone" json:"phone"` // several numbers separated by newlines or commas
	Email   string `bson:"email" json:"email"`
	Website string `bson:"website" json:"website"`
	Address string `bson:"address" json:"address"`
	Notes   string `bson:"notes" json:"notes"`
	Public  bool   `bson:"public" json:"public"` // public cards show up in every user's search; private ones only for their owner
}

type Card struct {
	ID         string `bson:"_id" json:"id"`
	CardFields `bson:",inline"`
	OwnerID    string    `bson:"ownerId" json:"-"`
	OwnerName  string    `bson:"ownerName" json:"ownerName"`
	PhotoID    string    `bson:"photoId,omitempty" json:"-"`         // Cloudinary public id, for deleting the photo
	PhotoURL   string    `bson:"photoUrl,omitempty" json:"photoUrl"` // signed link, only ever sent to people allowed to see the card
	Mine       bool      `bson:"-" json:"mine"`                      // whether the caller owns it
	CreatedAt  time.Time `bson:"createdAt" json:"createdAt"`
	UpdatedAt  time.Time `bson:"updatedAt" json:"updatedAt"`
}

type User struct {
	ID           string    `bson:"_id" json:"id"`
	Name         string    `bson:"name" json:"name"`
	Email        string    `bson:"email" json:"email"`
	PasswordHash []byte    `bson:"passwordHash" json:"-"`
	CreatedAt    time.Time `bson:"createdAt" json:"createdAt"`
}

// A session is one login. Its id is the hash of the token the app holds, so a database leak doesn't leak logins.
type session struct {
	ID        string    `bson:"_id"`
	UserID    string    `bson:"userId"`
	ExpiresAt time.Time `bson:"expiresAt"`
}

const (
	sessionTTL  = 180 * 24 * time.Hour
	maxFailures = 10 // wrong passwords for one email before its logins pause
	lockout     = 15 * time.Minute
)

type store struct {
	db                               *mongo.Database
	users, sessions, failures, cards *mongo.Collection
	photos                           *cloudinary // nil when CLOUDINARY_URL isn't set
}

func openStore(ctx context.Context, uri, dbName string) (*store, error) {
	client, err := mongo.Connect(options.Client().ApplyURI(uri))
	if err != nil {
		return nil, err
	}
	db := client.Database(dbName)
	s := &store{
		db:       db,
		users:    db.Collection("users"),
		sessions: db.Collection("sessions"),
		failures: db.Collection("loginFailures"),
		cards:    db.Collection("cards"),
	}
	expire := options.Index().SetExpireAfterSeconds(0) // MongoDB deletes these documents once expiresAt passes
	indexes := map[*mongo.Collection][]mongo.IndexModel{
		s.users:    {{Keys: bson.M{"email": 1}, Options: options.Index().SetUnique(true)}},
		s.sessions: {{Keys: bson.M{"expiresAt": 1}, Options: expire}},
		s.failures: {{Keys: bson.M{"expiresAt": 1}, Options: expire}},
		s.cards: {
			{Keys: bson.M{"ownerId": 1}},
			{Keys: bson.D{{Key: "public", Value: 1}, {Key: "updatedAt", Value: -1}}},
		},
	}
	for c, models := range indexes {
		if _, err := c.Indexes().CreateMany(ctx, models); err != nil {
			client.Disconnect(ctx)
			return nil, err
		}
	}
	return s, nil
}

func (s *store) handler() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("POST /api/auth/signup", s.signup)
	mux.HandleFunc("POST /api/auth/login", s.login)
	mux.HandleFunc("POST /api/auth/logout", s.authed(s.logout))
	mux.HandleFunc("GET /api/cards", s.authed(s.list))
	mux.HandleFunc("GET /api/cards/public", s.authed(s.searchPublic))
	mux.HandleFunc("POST /api/cards", s.authed(s.create))
	mux.HandleFunc("PUT /api/cards/{id}", s.authed(s.update))
	mux.HandleFunc("DELETE /api/cards/{id}", s.authed(s.delete))
	mux.HandleFunc("PUT /api/cards/{id}/image", s.authed(s.putImage))
	return mux
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

type credentials struct {
	Name     string `json:"name"`
	Email    string `json:"email"`
	Password string `json:"password"`
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
	if utf8.RuneCountInString(in.Password) < 8 {
		http.Error(w, "use a password of at least 8 characters", http.StatusBadRequest)
		return
	}
	hash, err := bcrypt.GenerateFromPassword([]byte(in.Password), bcrypt.DefaultCost)
	if errors.Is(err, bcrypt.ErrPasswordTooLong) {
		http.Error(w, "that password is too long (72 characters at most)", http.StatusBadRequest)
		return
	}
	if err != nil {
		serverError(w, err)
		return
	}
	u := User{ID: rand.Text(), Name: in.Name, Email: in.Email, PasswordHash: hash, CreatedAt: now()}
	_, err = s.users.InsertOne(r.Context(), u)
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

func (s *store) list(w http.ResponseWriter, r *http.Request, uid string) {
	s.writeCards(w, r, uid, bson.M{"ownerId": uid})
}

// searchPublic finds public cards from everyone, the caller's included, newest first.
func (s *store) searchPublic(w http.ResponseWriter, r *http.Request, uid string) {
	filter := bson.M{"public": true}
	if q := strings.TrimSpace(r.URL.Query().Get("q")); q != "" {
		text := bson.Regex{Pattern: regexp.QuoteMeta(q), Options: "i"}
		var anyField bson.A
		for _, f := range []string{"name", "title", "company", "phone", "email", "website", "address", "notes"} {
			anyField = append(anyField, bson.M{f: text})
		}
		filter["$or"] = anyField
	}
	// ponytail: a substring scan of public cards, capped at 50 results; add an Atlas Search index once there are too many to scan.
	s.writeCards(w, r, uid, filter, options.Find().SetSort(bson.D{{Key: "updatedAt", Value: -1}}).SetLimit(50))
}

func (s *store) writeCards(w http.ResponseWriter, r *http.Request, uid string, filter bson.M, opts ...options.Lister[options.FindOptions]) {
	cur, err := s.cards.Find(r.Context(), filter, opts...)
	if err != nil {
		serverError(w, err)
		return
	}
	list := []Card{} // the app parses a JSON array, so no results must be [] rather than null
	if err := cur.All(r.Context(), &list); err != nil {
		serverError(w, err)
		return
	}
	for i := range list {
		list[i].Mine = list[i].OwnerID == uid
	}
	writeJSON(w, http.StatusOK, list)
}

func (s *store) create(w http.ResponseWriter, r *http.Request, uid string) {
	f, ok := decodeCard(w, r)
	if !ok {
		return
	}
	var owner User
	if err := s.users.FindOne(r.Context(), bson.M{"_id": uid}).Decode(&owner); err != nil {
		serverError(w, err)
		return
	}
	t := now()
	c := Card{ID: rand.Text(), CardFields: f, OwnerID: uid, OwnerName: owner.Name, Mine: true, CreatedAt: t, UpdatedAt: t}
	if _, err := s.cards.InsertOne(r.Context(), c); err != nil {
		serverError(w, err)
		return
	}
	writeJSON(w, http.StatusCreated, c)
}

func (s *store) update(w http.ResponseWriter, r *http.Request, uid string) {
	f, ok := decodeCard(w, r)
	if !ok {
		return
	}
	if c, ok := s.change(w, r, uid, bson.M{"$set": f}); ok {
		writeJSON(w, http.StatusOK, c)
	}
}

func (s *store) delete(w http.ResponseWriter, r *http.Request, uid string) {
	var c Card
	if err := s.cards.FindOneAndDelete(r.Context(), owned(r, uid)).Decode(&c); err != nil {
		lookupError(w, r, err)
		return
	}
	s.dropPhoto(r, c.PhotoID)
	w.WriteHeader(http.StatusNoContent)
}

func (s *store) putImage(w http.ResponseWriter, r *http.Request, uid string) {
	if s.photos == nil {
		http.Error(w, "photo uploads aren't set up on the server yet (CLOUDINARY_URL)", http.StatusServiceUnavailable)
		return
	}
	b, err := io.ReadAll(http.MaxBytesReader(w, r.Body, 4<<20)) // Vercel caps request bodies at 4.5 MB
	if err != nil {
		http.Error(w, err.Error(), http.StatusBadRequest)
		return
	}
	if http.DetectContentType(b) != "image/jpeg" {
		http.Error(w, "photo must be a JPEG", http.StatusUnsupportedMediaType)
		return
	}
	var old Card
	if err := s.cards.FindOne(r.Context(), owned(r, uid)).Decode(&old); err != nil {
		lookupError(w, r, err)
		return
	}
	id, link, err := s.photos.upload(r.Context(), b)
	if err != nil {
		serverError(w, err)
		return
	}
	c, ok := s.change(w, r, uid, bson.M{"$set": bson.M{"photoId": id, "photoUrl": link}})
	if !ok {
		s.dropPhoto(r, id) // the card went away during the upload
		return
	}
	s.dropPhoto(r, old.PhotoID) // the photo it replaced
	writeJSON(w, http.StatusOK, c)
}

// change applies update to the caller's card {id} and returns the updated card; on failure it has already answered.
func (s *store) change(w http.ResponseWriter, r *http.Request, uid string, update bson.M) (Card, bool) {
	update["$currentDate"] = bson.M{"updatedAt": true}
	var c Card
	err := s.cards.FindOneAndUpdate(r.Context(), owned(r, uid), update,
		options.FindOneAndUpdate().SetReturnDocument(options.After)).Decode(&c)
	if err != nil {
		lookupError(w, r, err)
		return c, false
	}
	c.Mine = true
	return c, true
}

// dropPhoto deletes a photo from Cloudinary, finishing even if the app hangs up.
// ponytail: a failed delete is only logged and leaves the photo behind; sweep with Cloudinary's Admin API if that ever adds up.
func (s *store) dropPhoto(r *http.Request, id string) {
	if id == "" || s.photos == nil {
		return
	}
	if err := s.photos.destroy(context.WithoutCancel(r.Context()), id); err != nil {
		log.Print(err)
	}
}

// owned matches card {id} only when uid owns it, so nobody can change someone else's card by guessing its id.
func owned(r *http.Request, uid string) bson.M {
	return bson.M{"_id": r.PathValue("id"), "ownerId": uid}
}

// lookupError answers a failed single-card lookup: 404 when nothing matched, otherwise 500.
func lookupError(w http.ResponseWriter, r *http.Request, err error) {
	if errors.Is(err, mongo.ErrNoDocuments) {
		http.NotFound(w, r)
	} else {
		serverError(w, err)
	}
}

// decodeCard reads the owner-editable fields from the body; id, owner, photo and timestamps stay server-owned.
func decodeCard(w http.ResponseWriter, r *http.Request) (CardFields, bool) {
	var f CardFields
	if !decodeJSON(w, r, &f) {
		return f, false
	}
	for _, p := range []*string{&f.Name, &f.Title, &f.Company, &f.Phone, &f.Email, &f.Website, &f.Address, &f.Notes} {
		*p = strings.TrimSpace(*p)
	}
	if f.Name == "" && f.Company == "" {
		http.Error(w, "a card needs a name or a company", http.StatusBadRequest)
		return f, false
	}
	return f, true
}

func decodeCredentials(w http.ResponseWriter, r *http.Request) (credentials, bool) {
	var c credentials
	ok := decodeJSON(w, r, &c)
	c.Name, c.Email = strings.TrimSpace(c.Name), strings.ToLower(strings.TrimSpace(c.Email))
	return c, ok
}

func decodeJSON(w http.ResponseWriter, r *http.Request, v any) bool {
	if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, 64<<10)).Decode(v); err != nil {
		http.Error(w, "invalid JSON: "+err.Error(), http.StatusBadRequest)
		return false
	}
	return true
}

func bearer(r *http.Request) string {
	token, _ := strings.CutPrefix(r.Header.Get("Authorization"), "Bearer ")
	return token
}

func hashToken(token string) string {
	sum := sha256.Sum256([]byte(token))
	return hex.EncodeToString(sum[:])
}

// now is the current time as MongoDB stores it (UTC, milliseconds), so a card reads back exactly as it was written.
func now() time.Time { return time.Now().UTC().Truncate(time.Millisecond) }

func writeJSON(w http.ResponseWriter, status int, v any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	json.NewEncoder(w).Encode(v)
}

func serverError(w http.ResponseWriter, err error) {
	log.Print(err)
	http.Error(w, "server error, check the server log", http.StatusInternalServerError)
}

func main() {
	uri := os.Getenv("MONGODB_URI")
	if uri == "" {
		log.Fatal("set MONGODB_URI (MongoDB Atlas; Vercel's MongoDB Atlas integration sets it)")
	}
	s, err := openStore(context.Background(), uri, "cardify")
	if err != nil {
		log.Fatal(err)
	}
	if raw := os.Getenv("CLOUDINARY_URL"); raw != "" {
		if s.photos, err = newCloudinary(raw); err != nil {
			log.Fatal(err)
		}
	} else {
		log.Print("CLOUDINARY_URL isn't set, so photo uploads are off")
	}
	srv := &http.Server{
		Addr:              ":" + cmp.Or(os.Getenv("PORT"), "8080"), // Vercel sets PORT
		Handler:           s.handler(),
		ReadHeaderTimeout: 10 * time.Second,
	}
	log.Printf("cardify listening on %s", srv.Addr)
	log.Fatal(srv.ListenAndServe())
}
