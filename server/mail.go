package main

import (
	"context"
	"mime"
	"net/smtp"
	"os"
)

// gmail sends email from a Gmail account over SMTP, signing in with an app password
// (Google Account → Security → 2-Step Verification → App passwords).
type gmail struct{ sender, password string }

// newGmail reads GMAIL_SENDER and GMAIL_APP_PASSWORD; nil if either is missing.
func newGmail() *gmail {
	g := &gmail{os.Getenv("GMAIL_SENDER"), os.Getenv("GMAIL_APP_PASSWORD")}
	if g.sender == "" || g.password == "" {
		return nil
	}
	return g
}

// ponytail: net/smtp takes no context, so a stuck Gmail connection waits for Vercel's request timeout.
func (g *gmail) send(_ context.Context, to, subject, body string) error {
	msg := "From: Cardify <" + g.sender + ">\r\nTo: " + to + "\r\nSubject: " + mime.QEncoding.Encode("utf-8", subject) +
		"\r\nMIME-Version: 1.0\r\nContent-Type: text/plain; charset=UTF-8\r\n\r\n" + body
	// SendMail switches to TLS (STARTTLS) before signing in.
	return smtp.SendMail("smtp.gmail.com:587", smtp.PlainAuth("", g.sender, g.password, "smtp.gmail.com"), g.sender, []string{to}, []byte(msg))
}
