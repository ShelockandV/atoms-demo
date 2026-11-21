interface GoogleSignIn {
  initialize(options: { client_id: string; callback: (response: { credential: string }) => void }): void;
  renderButton(element: HTMLElement, options: { theme: "outline" | "filled_black"; size: "large"; shape: "pill"; width: string }): void;
}

type GoogleWindow = Window & { google?: { accounts: { id: GoogleSignIn } } };
let pending: Promise<GoogleSignIn> | undefined;

export function loadGoogleSignIn(): Promise<GoogleSignIn> {
  const current = () => (window as GoogleWindow).google?.accounts.id;
  const available = current();
  if (available) return Promise.resolve(available);
  if (!pending) {
    pending = new Promise<GoogleSignIn>((resolve, reject) => {
      const script = document.createElement("script");
      script.src = "https://accounts.google.com/gsi/client";
      script.async = true;
      const timeout = window.setTimeout(failure, 10000);
      function failure() {
        clearTimeout(timeout);
        script.remove();
        reject(new Error("无法加载 Google 登录"));
      }
      script.onerror = failure;
      script.onload = () => {
        const signIn = current();
        if (signIn) {
          clearTimeout(timeout);
          resolve(signIn);
        } else {
          failure();
        }
      };
      document.head.append(script);
    }).catch((error) => {
      pending = undefined;
      throw error;
    });
  }
  return pending;
}
