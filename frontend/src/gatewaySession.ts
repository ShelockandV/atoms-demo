import { shallowRef } from "vue";
import { api } from "./api";

interface GatewayTokens {
  access_token: string;
  refresh_token: string;
  expires_in: number;
}

interface GoogleProfile {
  name?: string;
  email?: string;
  picture?: string;
}

interface GatewayUser {
  googleProfile?: GoogleProfile;
  user_id: string;
  tenant_id: string;
  credential_type: string;
}

export const gatewayUser = shallowRef<GatewayUser>();
let accessToken = "";
let session: (GatewayTokens & { expiresAt: number }) | undefined;
let refreshPending: Promise<string> | undefined;

function remember(tokens: GatewayTokens) {
  accessToken = tokens.access_token;
  session = { ...tokens, expiresAt: Date.now() + tokens.expires_in * 1000 };
}

// These claims are used only for display, after the Gateway has accepted the credential.
function profileFromAcceptedGoogleToken(token: string, subject: string): GoogleProfile | undefined {
  try {
    const payload = token.split(".")[1].replace(/-/g, "+").replace(/_/g, "/");
    const profile = JSON.parse(new TextDecoder().decode(Uint8Array.from(atob(payload), (character) => character.charCodeAt(0))));
    if (profile.sub !== subject) return;
    const name = typeof profile.name === "string" ? profile.name.trim().slice(0, 120) : undefined;
    const email = profile.email_verified === true && typeof profile.email === "string" ? profile.email.slice(0, 254) : undefined;
    const picture = typeof profile.picture === "string" && /^https:\/\/lh[0-9]+\.googleusercontent\.com\//.test(profile.picture)
      ? profile.picture : undefined;
    return { name, email, picture };
  } catch {
    return undefined;
  }
}

export async function signInGateway(token: string, provider = "bearer") {
  const tokens = provider === "google"
    ? await api<GatewayTokens>("/v1/auth/register", "POST", { provider, token })
    : undefined;
  const bearer = tokens?.access_token ?? token;
  const identity = await api<GatewayUser>("/v1/auth/me", "GET", undefined, {
    headers: { Authorization: `Bearer ${bearer}` },
  });
  if (tokens) remember(tokens);
  else {
    accessToken = bearer;
    session = undefined;
  }
  gatewayUser.value = tokens
    ? { ...identity, googleProfile: profileFromAcceptedGoogleToken(token, identity.user_id) }
    : identity;
}

export function signOutGateway() {
  accessToken = "";
  session = undefined;
  gatewayUser.value = undefined;
}

async function gatewayToken() {
  const current = session;
  if (!current || Date.now() + 30000 < current.expiresAt) return accessToken;
  if (!refreshPending) {
    refreshPending = api<GatewayTokens>("/v1/auth/refresh", "POST", { refresh_token: current.refresh_token })
      .then((tokens) => {
        if (session === current) remember(tokens);
        return accessToken;
      })
      .catch((error) => {
        if (session === current) signOutGateway();
        throw error;
      })
      .finally(() => { refreshPending = undefined; });
  }
  return refreshPending;
}

export async function gatewayApi<T>(path: string, method = "GET", body?: unknown) {
  const token = await gatewayToken();
  return api<T>(path, method, body, token ? { headers: { Authorization: `Bearer ${token}` } } : {});
}
