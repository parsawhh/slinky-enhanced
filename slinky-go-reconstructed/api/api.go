package api

import (
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"time"
)

const baseURL = "https://api.slinkygg.com"

// Client is the Slinky licence/file API client.
type Client struct {
	http    *http.Client
	licKey  string
	hwid    string
}

// NewClient creates an API client.  The licence key and HWID are read from
// the environment / a local config file at startup.
func NewClient() *Client {
	return &Client{
		http:   &http.Client{Timeout: 15 * time.Second},
		licKey: readLicenceKey(),
		hwid:   machineID(),
	}
}

type licenceResponse struct {
	ExpiresIn float64 `json:"expires_in"` // days remaining
}

// GetLicenseRemainingDuration returns the number of days left on the licence.
func (c *Client) GetLicenseRemainingDuration() (float64, error) {
	req, err := http.NewRequest("GET", baseURL+"/licence/check", nil)
	if err != nil {
		return 0, err
	}
	c.addHeaders(req)

	resp, err := c.http.Do(req)
	if err != nil {
		return 0, err
	}
	defer resp.Body.Close()

	if resp.StatusCode != 200 {
		return 0, fmt.Errorf("licence check HTTP %d", resp.StatusCode)
	}

	var lr licenceResponse
	if err := json.NewDecoder(resp.Body).Decode(&lr); err != nil {
		return 0, err
	}
	return lr.ExpiresIn, nil
}

type fileResponse struct {
	URL  string `json:"url"`
	Size float64 `json:"size"` // MiB
}

// GetFile fetches a named payload file and writes it to the system temp dir,
// returning its local path.  Progress is logged as "%.1f MiB".
func (c *Client) GetFile(name string) (string, error) {
	req, err := http.NewRequest("GET", baseURL+"/files/"+name, nil)
	if err != nil {
		return "", err
	}
	c.addHeaders(req)

	resp, err := c.http.Do(req)
	if err != nil {
		return "", err
	}
	defer resp.Body.Close()

	if resp.StatusCode != 200 {
		return "", fmt.Errorf("GetFile(%q) HTTP %d", name, resp.StatusCode)
	}

	var fr fileResponse
	if err := json.NewDecoder(resp.Body).Decode(&fr); err != nil {
		return "", err
	}

	// Download the actual file from fr.URL.
	dest := filepath.Join(os.TempDir(), "slinky-payload.jar")
	if err := downloadFile(c.http, fr.URL, dest); err != nil {
		return "", err
	}
	return dest, nil
}

// addHeaders attaches the licence key and HWID to every request.
func (c *Client) addHeaders(r *http.Request) {
	r.Header.Set("X-License-Key", c.licKey)
	r.Header.Set("X-HWID", c.hwid)
	r.Header.Set("User-Agent", "slinky-loader/1.0")
}

func downloadFile(client *http.Client, url, dest string) error {
	resp, err := client.Get(url)
	if err != nil {
		return err
	}
	defer resp.Body.Close()

	f, err := os.Create(dest)
	if err != nil {
		return err
	}
	defer f.Close()

	_, err = io.Copy(f, resp.Body)
	return err
}

func readLicenceKey() string {
	if k := os.Getenv("SLINKY_KEY"); k != "" {
		return k
	}
	// Fall back to a local key file.
	home, _ := os.UserHomeDir()
	data, _ := os.ReadFile(filepath.Join(home, ".slinky", "key"))
	return string(data)
}

// machineID returns a stable hardware identifier for the current machine.
// The real implementation hashes CPU serial / motherboard UUID etc.; we
// return a placeholder here.
func machineID() string {
	return "00000000-0000-0000-0000-000000000000"
}
