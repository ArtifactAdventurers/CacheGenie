package dev.gruff.hardstop.cachegenie.actions.index;

import java.io.FileWriter;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.json.JSONArray;
import org.json.JSONObject;

public class MavenArtifactFetcher {

    private static final String BASE_URL = "https://search.maven.org/solrsearch/select";
    private static final int ROWS = 100; // Number of rows per request
    private static final int DELAY = 1000; // Delay in milliseconds between requests to avoid overwhelming the server
    private static final String OUTPUT_FILE = "artifacts.json"; // Output file name

    public static void main(String[] args) {
        fetchAndSaveArtifacts();
        System.out.println("Artifacts have been saved to " + OUTPUT_FILE);
    }

    public static void fetchAndSaveArtifacts() {
        int start = 0;
        boolean firstBatch = true;

        // Initialize HttpClient
        HttpClient client = HttpClient.newHttpClient();

        try (FileWriter fileWriter = new FileWriter(OUTPUT_FILE)) {
            // Start JSON array in the output file
            fileWriter.write("[");

            while (true) {
                // Prepare the request URL with parameters
                String requestUrl = String.format("%s?q=*:*&rows=%d&start=%d&wt=json", BASE_URL, ROWS, start);

                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(requestUrl))
                        .GET()
                        .build();

                try {
                    HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                    JSONObject jsonResponse = new JSONObject(response.body());

                    // Check if the response has the expected structure
                    if (jsonResponse.has("response") && jsonResponse.getJSONObject("response").has("docs")) {
                        JSONArray artifacts = jsonResponse.getJSONObject("response").getJSONArray("docs");

                        // Save the current batch of artifacts to the file
                        saveToFile(artifacts, fileWriter, firstBatch);
                        System.out.println("saved "+artifacts.length()+" entries");
                        firstBatch = false;

                        // Check if there are more artifacts to fetch
                        if (artifacts.length() < ROWS) {
                            break; // No more artifacts left to fetch
                        } else {
                            start += ROWS; // Move to the next set of artifacts
                        }
                    } else {
                        System.out.println("Unexpected response structure or no more data.");
                        break; // Exit if the response structure is unexpected
                    }

                } catch (IOException | InterruptedException e) {
                    System.out.println("An error occurred: " + e.getMessage());
                    break;
                }

                Thread.sleep(DELAY); // Pause to avoid overloading the server
            }

            // Close the JSON array in the output file
            fileWriter.write("]");
        } catch (IOException e) {
            System.out.println("An error occurred while writing to the file: " + e.getMessage());
        } catch (InterruptedException e) {
            System.out.println("An error occurred during the request delay: " + e.getMessage());
        }
    }

    public static void saveToFile(JSONArray artifacts, FileWriter fileWriter, boolean isFirstBatch) throws IOException {
        if (!isFirstBatch) {
            fileWriter.write(","); // Add a comma before appending the next batch
        }

        // Write the artifacts JSON array to the file without outer brackets
        String artifactsString = artifacts.toString().substring(1, artifacts.toString().length() - 1);
        fileWriter.write(artifactsString);
    }
}
