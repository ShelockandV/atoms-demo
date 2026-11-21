import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

class LoopbackProbe {
    public static void main(String[] args) throws Exception {
        long pid = Long.parseLong(args[0]);
        var process = ProcessHandle.of(pid).orElseThrow();
        var client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(500))
                .version(HttpClient.Version.HTTP_1_1)
                .build();
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:18080" + args[1]))
                .timeout(Duration.ofSeconds(1))
                .GET()
                .build();
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        int lastStatus = 0;
        while (process.isAlive() && System.nanoTime() < deadline) {
            try {
                var response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
                try (var body = response.body()) {
                    lastStatus = response.statusCode();
                    if (lastStatus >= 200 && lastStatus < 300) {
                        System.out.println("HTTP probe returned " + lastStatus + " for " + args[1]);
                        return;
                    }
                }
            } catch (java.io.IOException unavailable) {
                // The application may still be starting.
            }
            Thread.sleep(250);
        }
        throw new IllegalStateException(process.isAlive()
                ? "HTTP probe did not return 2xx; last status: " + lastStatus
                : "The application exited before the HTTP probe succeeded");
    }
}
