const ALLOWED_BASE_TOPIC_SHA256 = "c7d174f5a4e708de4e30faa6dbb478698e3073bc84cb32a7ebb3c3d932a3ab96";
const ORIGIN = "https://ntfy.sh";

function hex(bytes) {
  return [...new Uint8Array(bytes)].map(b => b.toString(16).padStart(2, "0")).join("");
}

async function sha256(text) {
  return hex(await crypto.subtle.digest("SHA-256", new TextEncoder().encode(text)));
}

function baseTopicFrom(topic) {
  if (topic.endsWith("-readsync")) return topic.slice(0, -9);
  if (topic.endsWith("-rs")) return topic.slice(0, -3);
  return topic;
}

export default {
  async fetch(request) {
    const incoming = new URL(request.url);

    if (incoming.pathname === "/" || incoming.pathname === "/health") {
      return Response.json({ ok: true, service: "ml-central-relay", upstream: "ntfy" }, {
        headers: { "cache-control": "no-store" }
      });
    }

    const parts = incoming.pathname.split("/").filter(Boolean);
    if (!parts.length) return new Response("Not found", { status: 404 });

    let topic;
    try {
      topic = decodeURIComponent(parts[0]);
    } catch {
      return new Response("Bad request", { status: 400 });
    }

    const baseTopic = baseTopicFrom(topic);
    const digest = await sha256(baseTopic);
    if (digest !== ALLOWED_BASE_TOPIC_SHA256) {
      return new Response("Forbidden", { status: 403 });
    }

    if (!["GET", "HEAD", "POST", "PUT"].includes(request.method)) {
      return new Response("Method not allowed", { status: 405 });
    }

    const target = new URL(incoming.pathname + incoming.search, ORIGIN);
    const headers = new Headers(request.headers);
    headers.delete("host");
    headers.delete("cf-connecting-ip");
    headers.delete("cf-ray");
    headers.delete("x-forwarded-for");
    headers.set("x-ml-central-relay", "1");

    const init = {
      method: request.method,
      headers,
      redirect: "manual"
    };

    if (request.method !== "GET" && request.method !== "HEAD") {
      init.body = request.body;
    }

    try {
      // Se devuelve el body sin leerlo para conservar streaming/NDJSON de ntfy.
      return await fetch(new Request(target.toString(), init));
    } catch (err) {
      return Response.json({ ok: false, error: String(err) }, {
        status: 502,
        headers: { "cache-control": "no-store" }
      });
    }
  }
};
