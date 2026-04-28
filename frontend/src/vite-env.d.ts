/// <reference types="vite/client" />

interface ImportMetaEnv {
  /**
   * Override the API base URL. Empty string (default) means same-origin —
   * the browser hits `/api/...` and Vite/nginx proxies it to the backend.
   * Set to e.g. `http://localhost:8080` to bypass the proxy.
   */
  readonly VITE_API_BASE_URL?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
