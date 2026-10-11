package update

import (
	"encoding/json"
	"fmt"
	"net/http"
	"time"
)

const updateURL = "https://api.slinkygg.com/loader/version"

// releaser is the minimal interface the update package needs from api.Client.
type releaser interface {
	Do(req *http.Request) (*http.Response, error)
}

type versionResponse struct {
	Latest  string `json:"latest"`
	Current string `json:"current"`
}

// apiClient is used to reach the update endpoint; satisfied by api.Client.
type apiClient interface {
	GetFile(name string) (string, error)
}

// Check queries the server for the latest loader version.  If a newer version
// is available it downloads and replaces the running executable.
func Check(client apiClient) error {
	hc := &http.Client{Timeout: 10 * time.Second}
	resp, err := hc.Get(updateURL)
	if err != nil {
		return err
	}
	defer resp.Body.Close()

	if resp.StatusCode != 200 {
		return fmt.Errorf("version check HTTP %d", resp.StatusCode)
	}

	var vr versionResponse
	if err := json.NewDecoder(resp.Body).Decode(&vr); err != nil {
		return err
	}

	if vr.Latest == vr.Current {
		return nil // already up to date
	}

	// Download new loader binary and replace ourselves.
	newBin, err := client.GetFile("loader")
	if err != nil {
		return fmt.Errorf("download update: %w", err)
	}
	return replaceExecutable(newBin)
}
