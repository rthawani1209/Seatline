import type { NextConfig } from "next";

// Everything the app loads comes from this site (fonts are bundled by next/font, the API is
// proxied at /api). Next's own inline bootstrap scripts need 'unsafe-inline'; the dev server's
// hot reload also needs eval.
const csp = [
  "default-src 'self'",
  `script-src 'self' 'unsafe-inline'${process.env.NODE_ENV === "production" ? "" : " 'unsafe-eval'"}`,
  "style-src 'self' 'unsafe-inline'",
  "img-src 'self' data: blob:",
  "font-src 'self'",
  "connect-src 'self'",
  "media-src 'self' blob:",
  "object-src 'none'",
  "base-uri 'self'",
  "form-action 'self'",
  "frame-ancestors 'none'",
].join("; ");

const config: NextConfig = {
  // a small self-contained server for the Docker image
  output: "standalone",
  poweredByHeader: false,
  agentRules: false,
  async headers() {
    return [
      {
        source: "/:path*",
        headers: [
          { key: "X-Content-Type-Options", value: "nosniff" },
          { key: "Referrer-Policy", value: "same-origin" },
          { key: "X-Frame-Options", value: "DENY" },
          // the door scanner needs the camera; nothing needs the rest
          { key: "Permissions-Policy", value: "camera=(self), microphone=(), geolocation=()" },
          { key: "Content-Security-Policy", value: csp },
          // browsers only honour this over HTTPS, so it's harmless on a local http:// run
          { key: "Strict-Transport-Security", value: "max-age=31536000; includeSubDomains" },
        ],
      },
    ];
  },
};

export default config;
