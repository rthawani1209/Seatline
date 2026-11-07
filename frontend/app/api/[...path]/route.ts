// Forwards /api/* to the Spring API, so the browser only ever talks to this origin (no CORS,
// and the API's address can change per deployment without rebuilding the app).
import type { NextRequest } from "next/server";

const API = process.env.SEATLINE_API_URL ?? "http://localhost:8080";

// only what the API needs; cookies and the rest stay here
const PASS = ["authorization", "content-type", "accept", "idempotency-key"];

async function forward(req: NextRequest, ctx: { params: Promise<{ path: string[] }> }) {
  const { path } = await ctx.params;
  const url = `${API}/${path.map(encodeURIComponent).join("/")}${req.nextUrl.search}`;

  const headers = new Headers();
  for (const h of PASS) {
    const v = req.headers.get(h);
    if (v) headers.set(h, v);
  }
  // login rate limits are per client address
  const ip = req.headers.get("x-forwarded-for")?.split(",")[0]?.trim();
  if (ip) headers.set("x-forwarded-for", ip);

  const hasBody = req.method !== "GET" && req.method !== "HEAD";
  try {
    const res = await fetch(url, {
      method: req.method,
      headers,
      body: hasBody ? await req.arrayBuffer() : undefined,
      cache: "no-store",
      redirect: "manual",
    });
    const out = new Headers();
    const type = res.headers.get("content-type");
    if (type) out.set("content-type", type);
    out.set("cache-control", "no-store");
    return new Response(res.status === 204 ? null : res.body, { status: res.status, headers: out });
  } catch {
    return Response.json({ error: "api_unreachable" }, { status: 502 });
  }
}

export { forward as GET, forward as POST, forward as PUT, forward as DELETE, forward as PATCH };
