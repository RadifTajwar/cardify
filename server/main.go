// Command server is the Cardify API: visiting cards and their photos, stored in Postgres.
package main

import (
	"cmp"
	"context"
	"crypto/rand"
	"crypto/subtle"
	"encoding/json"
	"errors"
	"io"
	"log"
	"net/http"
	"os"
	"strings"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
)

// Card's fields are in cardColumns order, so query rows scan straight into it.
type Card struct {
	ID        string    `json:"id"`
	Name      string    `json:"name"`
	Title     string    `json:"title"`
	Company   string    `json:"company"`
	Phone     string    `json:"phone"` // several numbers separated by newlines or commas
	Email     string    `json:"email"`
	Website   string    `json:"website"`
	Address   string    `json:"address"`
	Notes     string    `json:"notes"`
	HasImage  bool      `json:"hasImage"`
	CreatedAt time.Time `json:"createdAt"`
	UpdatedAt time.Time `json:"updatedAt"`
}

const cardColumns = "id, name, title, company, phone, email, website, address, notes, image IS NOT NULL, created_at, updated_at"

// ponytail: photos live in the table; move them to object storage if they outgrow the database.
const schema = `CREATE TABLE IF NOT EXISTS cards (
	id         text PRIMARY KEY,
	name       text NOT NULL,
	title      text NOT NULL,
	company    text NOT NULL,
	phone      text NOT NULL,
	email      text NOT NULL,
	website    text NOT NULL,
	address    text NOT NULL,
	notes      text NOT NULL,
	image      bytea,
	created_at timestamptz NOT NULL DEFAULT now(),
	updated_at timestamptz NOT NULL DEFAULT now()
)`

type store struct{ db *pgxpool.Pool }

func openStore(ctx context.Context, url string) (*store, error) {
	cfg, err := pgxpool.ParseConfig(url)
	if err != nil {
		return nil, err
	}
	// Neon's connection pooler can't keep prepared statements, so send each query in one round trip instead.
	cfg.ConnConfig.DefaultQueryExecMode = pgx.QueryExecModeExec
	db, err := pgxpool.NewWithConfig(ctx, cfg)
	if err != nil {
		return nil, err
	}
	if _, err := db.Exec(ctx, schema); err != nil {
		db.Close()
		return nil, err
	}
	return &store{db}, nil
}

func (s *store) handler(token string) http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("GET /api/cards", s.list)
	mux.HandleFunc("POST /api/cards", s.create)
	mux.HandleFunc("PUT /api/cards/{id}", s.update)
	mux.HandleFunc("DELETE /api/cards/{id}", s.delete)
	mux.HandleFunc("GET /api/cards/{id}/image", s.getImage)
	mux.HandleFunc("PUT /api/cards/{id}/image", s.putImage)

	// ponytail: one shared token for a single-user app; add real accounts if others ever sign in.
	want := []byte("Bearer " + token)
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if subtle.ConstantTimeCompare([]byte(r.Header.Get("Authorization")), want) != 1 {
			http.Error(w, "invalid or missing token", http.StatusUnauthorized)
			return
		}
		mux.ServeHTTP(w, r)
	})
}

func (s *store) list(w http.ResponseWriter, r *http.Request) {
	rows, _ := s.db.Query(r.Context(), "SELECT "+cardColumns+" FROM cards ORDER BY lower(coalesce(nullif(name, ''), company)), id")
	list, err := pgx.CollectRows(rows, pgx.RowToStructByPos[Card])
	if err != nil {
		serverError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, list)
}

func (s *store) create(w http.ResponseWriter, r *http.Request) {
	c, ok := decodeCard(w, r)
	if !ok {
		return
	}
	s.one(w, r, http.StatusCreated,
		`INSERT INTO cards (id, name, title, company, phone, email, website, address, notes)
		VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9) RETURNING `+cardColumns,
		rand.Text(), c.Name, c.Title, c.Company, c.Phone, c.Email, c.Website, c.Address, c.Notes)
}

func (s *store) update(w http.ResponseWriter, r *http.Request) {
	c, ok := decodeCard(w, r)
	if !ok {
		return
	}
	s.one(w, r, http.StatusOK,
		`UPDATE cards SET name = $2, title = $3, company = $4, phone = $5, email = $6, website = $7,
		address = $8, notes = $9, updated_at = now() WHERE id = $1 RETURNING `+cardColumns,
		r.PathValue("id"), c.Name, c.Title, c.Company, c.Phone, c.Email, c.Website, c.Address, c.Notes)
}

func (s *store) delete(w http.ResponseWriter, r *http.Request) {
	tag, err := s.db.Exec(r.Context(), "DELETE FROM cards WHERE id = $1", r.PathValue("id"))
	switch {
	case err != nil:
		serverError(w, err)
	case tag.RowsAffected() == 0:
		http.NotFound(w, r)
	default:
		w.WriteHeader(http.StatusNoContent)
	}
}

func (s *store) getImage(w http.ResponseWriter, r *http.Request) {
	var img []byte
	err := s.db.QueryRow(r.Context(), "SELECT image FROM cards WHERE id = $1 AND image IS NOT NULL", r.PathValue("id")).Scan(&img)
	if errors.Is(err, pgx.ErrNoRows) {
		http.NotFound(w, r)
		return
	}
	if err != nil {
		serverError(w, err)
		return
	}
	w.Header().Set("Content-Type", "image/jpeg")
	// The app puts the card's updatedAt in photo URLs, so a URL's bytes never change.
	// "private" keeps Vercel's CDN from caching someone's card for others.
	w.Header().Set("Cache-Control", "private, max-age=31536000, immutable")
	w.Write(img)
}

func (s *store) putImage(w http.ResponseWriter, r *http.Request) {
	b, err := io.ReadAll(http.MaxBytesReader(w, r.Body, 4<<20)) // Vercel caps request bodies at 4.5 MB
	if err != nil {
		http.Error(w, err.Error(), http.StatusBadRequest)
		return
	}
	if http.DetectContentType(b) != "image/jpeg" {
		http.Error(w, "photo must be a JPEG", http.StatusUnsupportedMediaType)
		return
	}
	s.one(w, r, http.StatusOK,
		"UPDATE cards SET image = $2, updated_at = now() WHERE id = $1 RETURNING "+cardColumns,
		r.PathValue("id"), b)
}

// one runs a query returning a single card and writes it, or 404 when no card matched.
func (s *store) one(w http.ResponseWriter, r *http.Request, status int, sql string, args ...any) {
	rows, _ := s.db.Query(r.Context(), sql, args...)
	c, err := pgx.CollectExactlyOneRow(rows, pgx.RowToStructByPos[Card])
	switch {
	case errors.Is(err, pgx.ErrNoRows):
		http.NotFound(w, r)
	case err != nil:
		serverError(w, err)
	default:
		writeJSON(w, status, c)
	}
}

// decodeCard reads the editable fields from the body; id, photo and timestamps stay server-owned.
func decodeCard(w http.ResponseWriter, r *http.Request) (Card, bool) {
	var in Card
	if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, 64<<10)).Decode(&in); err != nil {
		http.Error(w, "invalid JSON: "+err.Error(), http.StatusBadRequest)
		return Card{}, false
	}
	t := strings.TrimSpace
	c := Card{
		Name: t(in.Name), Title: t(in.Title), Company: t(in.Company), Phone: t(in.Phone),
		Email: t(in.Email), Website: t(in.Website), Address: t(in.Address), Notes: t(in.Notes),
	}
	if c.Name == "" && c.Company == "" {
		http.Error(w, "a card needs a name or a company", http.StatusBadRequest)
		return Card{}, false
	}
	return c, true
}

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
	token, dbURL := os.Getenv("CARDIFY_TOKEN"), os.Getenv("DATABASE_URL")
	if token == "" || dbURL == "" {
		log.Fatal("set CARDIFY_TOKEN (the secret the app sends) and DATABASE_URL (Postgres; Vercel's Neon integration sets it)")
	}
	s, err := openStore(context.Background(), dbURL)
	if err != nil {
		log.Fatal(err)
	}
	srv := &http.Server{
		Addr:              ":" + cmp.Or(os.Getenv("PORT"), "8080"), // Vercel sets PORT
		Handler:           s.handler(token),
		ReadHeaderTimeout: 10 * time.Second,
	}
	log.Printf("cardify listening on %s", srv.Addr)
	log.Fatal(srv.ListenAndServe())
}
