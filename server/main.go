// Command server is the Cardify API: accounts and their visiting cards, stored in MongoDB, with card photos on Cloudinary.
package main

import (
	"cmp"
	"context"
	"crypto/rand"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log"
	"net/http"
	"os"
	"regexp"
	"strings"
	"time"

	"go.mongodb.org/mongo-driver/v2/bson"
	"go.mongodb.org/mongo-driver/v2/mongo"
	"go.mongodb.org/mongo-driver/v2/mongo/options"
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
	PasswordHash []byte    `bson:"passwordHash,omitempty" json:"-"` // none for accounts made with Google, until a password reset
	GoogleID     string    `bson:"googleId,omitempty" json:"-"`
	CreatedAt    time.Time `bson:"createdAt" json:"createdAt"`
}

type store struct {
	db                                       *mongo.Database
	users, sessions, failures, resets, cards *mongo.Collection
	photos                                   *cloudinary                                               // nil when CLOUDINARY_URL isn't set
	googleClientID                           string                                                    // empty when Google sign-in isn't set up
	tokenInfoURL                             string                                                    // Google's ID token checker; tests swap in a fake
	sendMail                                 func(ctx context.Context, to, subject, body string) error // nil when email isn't set up
}

func openStore(ctx context.Context, uri, dbName string) (*store, error) {
	client, err := mongo.Connect(options.Client().ApplyURI(uri))
	if err != nil {
		return nil, err
	}
	db := client.Database(dbName)
	s := &store{
		db:           db,
		users:        db.Collection("users"),
		sessions:     db.Collection("sessions"),
		failures:     db.Collection("loginFailures"),
		resets:       db.Collection("passwordResets"),
		cards:        db.Collection("cards"),
		tokenInfoURL: "https://oauth2.googleapis.com/tokeninfo",
	}
	expire := func(field string) mongo.IndexModel { // MongoDB deletes these documents once field's time passes
		return mongo.IndexModel{Keys: bson.M{field: 1}, Options: options.Index().SetExpireAfterSeconds(0)}
	}
	indexes := map[*mongo.Collection][]mongo.IndexModel{
		s.users: {
			{Keys: bson.M{"email": 1}, Options: options.Index().SetUnique(true)},
			{Keys: bson.M{"googleId": 1}, Options: options.Index().SetUnique(true).SetSparse(true)},
		},
		s.sessions: {expire("expiresAt")},
		s.failures: {expire("expiresAt")},
		s.resets:   {expire("purgeAt")},
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
	mux.HandleFunc("POST /api/auth/google", s.google)
	mux.HandleFunc("POST /api/auth/forgot", s.forgot)
	mux.HandleFunc("POST /api/auth/reset", s.reset)
	mux.HandleFunc("POST /api/auth/logout", s.authed(s.logout))
	mux.HandleFunc("GET /api/cards", s.authed(s.list))
	mux.HandleFunc("GET /api/cards/public", s.authed(s.searchPublic))
	mux.HandleFunc("POST /api/cards", s.authed(s.create))
	mux.HandleFunc("PUT /api/cards/{id}", s.authed(s.update))
	mux.HandleFunc("DELETE /api/cards/{id}", s.authed(s.delete))
	mux.HandleFunc("PUT /api/cards/{id}/image", s.authed(s.putImage))
	return mux
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

func decodeJSON(w http.ResponseWriter, r *http.Request, v any) bool {
	if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, 64<<10)).Decode(v); err != nil {
		http.Error(w, "invalid JSON: "+err.Error(), http.StatusBadRequest)
		return false
	}
	return true
}

// doJSON sends req to another service and decodes its 200 answer's JSON into out.
func doJSON(req *http.Request, out any) error {
	res, err := (&http.Client{Timeout: 30 * time.Second}).Do(req)
	if err != nil {
		return err
	}
	defer res.Body.Close()
	if res.StatusCode != http.StatusOK {
		msg, _ := io.ReadAll(io.LimitReader(res.Body, 1<<10))
		return fmt.Errorf("%s %s%s: %s: %s", req.Method, req.URL.Host, req.URL.Path, res.Status, msg) // no query: it may hold a token
	}
	return json.NewDecoder(res.Body).Decode(out)
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
	if s.googleClientID = os.Getenv("GOOGLE_CLIENT_ID"); s.googleClientID == "" {
		log.Print("GOOGLE_CLIENT_ID isn't set, so Google sign-in is off")
	}
	if g := newGmail(); g != nil {
		s.sendMail = g.send
	} else {
		log.Print("GMAIL_* isn't set, so password reset emails are off")
	}
	srv := &http.Server{
		Addr:              ":" + cmp.Or(os.Getenv("PORT"), "8080"), // Vercel sets PORT
		Handler:           s.handler(),
		ReadHeaderTimeout: 10 * time.Second,
	}
	log.Printf("cardify listening on %s", srv.Addr)
	log.Fatal(srv.ListenAndServe())
}
