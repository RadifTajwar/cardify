package main

import (
	"context"
	"crypto/rand"
	"crypto/sha1"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"maps"
	"net/http"
	"net/url"
	"slices"
	"strconv"
	"strings"
	"time"
)

// cloudinary stores card photos. They're uploaded as "authenticated", so a photo only opens through the signed
// link its upload returns, and the server hands that link only to people allowed to see the card.
type cloudinary struct{ api, cloud, key, secret string }

// newCloudinary reads CLOUDINARY_URL, which looks like cloudinary://<api_key>:<api_secret>@<cloud_name>.
func newCloudinary(raw string) (*cloudinary, error) {
	bad := errors.New("CLOUDINARY_URL must look like cloudinary://<api_key>:<api_secret>@<cloud_name>") // never echo it: it holds the secret
	u, err := url.Parse(raw)
	if err != nil {
		return nil, bad
	}
	secret, _ := u.User.Password()
	if u.Scheme != "cloudinary" || u.Host == "" || u.User.Username() == "" || secret == "" {
		return nil, bad
	}
	return &cloudinary{"https://api.cloudinary.com", u.Host, u.User.Username(), secret}, nil
}

// upload stores a JPEG and returns its public id (to delete it later) and its signed link.
func (c *cloudinary) upload(ctx context.Context, jpeg []byte) (id, link string, err error) {
	var res struct {
		PublicID  string `json:"public_id"`
		SecureURL string `json:"secure_url"`
	}
	err = c.call(ctx, "upload", url.Values{
		"file":      {"data:image/jpeg;base64," + base64.StdEncoding.EncodeToString(jpeg)},
		"public_id": {"cardify/" + rand.Text()},
		"type":      {"authenticated"},
	}, &res)
	return res.PublicID, res.SecureURL, err
}

// destroy deletes a photo and clears it from Cloudinary's CDN.
func (c *cloudinary) destroy(ctx context.Context, id string) error {
	var res struct {
		Result string `json:"result"`
	}
	if err := c.call(ctx, "destroy", url.Values{"public_id": {id}, "type": {"authenticated"}, "invalidate": {"true"}}, &res); err != nil {
		return err
	}
	if res.Result != "ok" && res.Result != "not found" {
		return fmt.Errorf("cloudinary destroy %s: %s", id, res.Result)
	}
	return nil
}

// call makes a signed Upload API request and decodes the JSON answer into out.
func (c *cloudinary) call(ctx context.Context, action string, params url.Values, out any) error {
	// The signature is the SHA-1 of the sorted params (all but file and api_key), then the API secret.
	params.Set("timestamp", strconv.FormatInt(time.Now().Unix(), 10))
	var signed []string
	for _, k := range slices.Sorted(maps.Keys(params)) {
		if k != "file" {
			signed = append(signed, k+"="+params.Get(k))
		}
	}
	sum := sha1.Sum([]byte(strings.Join(signed, "&") + c.secret))
	params.Set("signature", hex.EncodeToString(sum[:]))
	params.Set("api_key", c.key)

	req, err := http.NewRequestWithContext(ctx, http.MethodPost, c.api+"/v1_1/"+c.cloud+"/image/"+action, strings.NewReader(params.Encode()))
	if err != nil {
		return err
	}
	req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	res, err := (&http.Client{Timeout: 30 * time.Second}).Do(req)
	if err != nil {
		return err
	}
	defer res.Body.Close()
	if res.StatusCode != http.StatusOK {
		msg, _ := io.ReadAll(io.LimitReader(res.Body, 1<<10))
		return fmt.Errorf("cloudinary %s: %s: %s", action, res.Status, msg)
	}
	return json.NewDecoder(res.Body).Decode(out)
}
