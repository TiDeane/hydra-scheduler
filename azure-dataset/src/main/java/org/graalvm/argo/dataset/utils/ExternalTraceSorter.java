package org.graalvm.argo.dataset.utils;

import java.io.*;
import java.util.*;

public class ExternalTraceSorter {

    /* Adjust based on your available RAM. 1 million lines is roughly 100-200MB of heap. */
    private static final int CHUNK_SIZE = 1_000_000; 

    /**
     * Helper record to bind a parsed timestamp to its raw CSV line.
     * This prevents us from having to call .split(",") millions of times during sorting.
     */
    private record SortableLine(String line, int timestamp) implements Comparable<SortableLine> {
        @Override
        public int compareTo(SortableLine o) {
            return Integer.compare(this.timestamp, o.timestamp);
        }
    }

    /**
     * Helper class for the K-Way Merge phase.
     */
    private static class MergeItem implements Comparable<MergeItem> {
      String line;
      int timestamp;
      int fileIndex; // tie-breaker for stability
      BufferedReader reader;

      public MergeItem(String line, int timestamp, int fileIndex, BufferedReader reader) {
          this.line = line;
          this.timestamp = timestamp;
          this.fileIndex = fileIndex;
          this.reader = reader;
      }

      @Override
      public int compareTo(MergeItem o) {
          int cmp = Integer.compare(this.timestamp, o.timestamp);
          if (cmp != 0) return cmp;
          return Integer.compare(this.fileIndex, o.fileIndex); // stable: earlier chunk wins
      }
  }

    public static void sortTraceByTimestamp(String inputFile, String outputFile, boolean hasHeader) throws IOException {
        List<File> tempFiles = new ArrayList<>();
        String headerLine = null;

        /* SPLIT AND SORT CHUNKS */
        try (BufferedReader reader = new BufferedReader(new FileReader(inputFile))) {
            if (hasHeader) {
                headerLine = reader.readLine();
            }

            List<SortableLine> currentChunk = new ArrayList<>(CHUNK_SIZE);
            String line;

            while ((line = reader.readLine()) != null) {
                String[] splitRow = line.split(",");
                int timestamp = 0;
                try {
                  timestamp = Integer.parseInt(splitRow[4]);
                } catch (Exception e) {
                  System.err.println("Error parsing timestamp from line: " + line);
                  throw e;
                }
                currentChunk.add(new SortableLine(line, timestamp));

                if (currentChunk.size() >= CHUNK_SIZE) {
                    tempFiles.add(sortAndSaveChunk(currentChunk));
                    currentChunk.clear(); // Free memory for the next chunk
                }
            }
            
            /* Sort and save the final, partially-filled chunk */
            if (!currentChunk.isEmpty()) {
                tempFiles.add(sortAndSaveChunk(currentChunk));
            }
        }

        /* K-WAY MERGE */
        PriorityQueue<MergeItem> pq = new PriorityQueue<>();
        List<BufferedReader> activeReaders = new ArrayList<>();

        try (BufferedWriter writer = new BufferedWriter(new FileWriter(outputFile))) {
            if (headerLine != null) {
                writer.write(headerLine);
                writer.newLine();
            }

            /* Initialize the Priority Queue with the first line of EVERY temp file */
            for (int i = 0; i < tempFiles.size(); i++) {
                BufferedReader br = new BufferedReader(new FileReader(tempFiles.get(i)));
                activeReaders.add(br);
                String line = br.readLine();
                if (line != null) {
                    String[] splitRow = line.split(",");
                    int timestamp = Integer.parseInt(splitRow[4]);
                    pq.add(new MergeItem(line, timestamp, i, br));
                }
            }

            /* Merge until all files are empty */
            while (!pq.isEmpty()) {
                MergeItem smallest = pq.poll();
                
                /* Write the smallest line to the final file */
                writer.write(smallest.line);
                writer.newLine();

                /* Read the next line from the file that "won" */
                String nextLine = smallest.reader.readLine();
                if (nextLine != null) {
                    String[] splitRow = nextLine.split(",");
                    int timestamp = Integer.parseInt(splitRow[4]);
                    smallest.line = nextLine;
                    smallest.timestamp = timestamp;
                    pq.add(smallest);
                }
            }
        } finally {
            /* Clean up resources and temporary files */
            for (BufferedReader br : activeReaders) {
                try { br.close(); } catch (IOException ignored) {}
            }
            for (File tempFile : tempFiles) {
                tempFile.delete(); 
            }
        }
    }

    private static File sortAndSaveChunk(List<SortableLine> chunk) throws IOException {
        Collections.sort(chunk);
        
        File tempFile = File.createTempFile("trace_chunk_", ".tmp");
        tempFile.deleteOnExit(); // Safety net in case the program crashes

        try (BufferedWriter bw = new BufferedWriter(new FileWriter(tempFile))) {
            for (SortableLine sl : chunk) {
                bw.write(sl.line());
                bw.newLine();
            }
        }
        return tempFile;
    }
}