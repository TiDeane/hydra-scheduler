package org.graalvm.argo.dataset.utility.forecasting;

import java.util.Arrays;
import java.util.Comparator;
import java.util.stream.IntStream;
import org.jtransforms.fft.DoubleFFT_1D;

public class Predictor {

  private static final int HARMONICS = 10;
  
  public static double[] meanPredict(int[] train, int horizon) {
    double[] prediction = new double[horizon];
    if (train == null || train.length == 0) return prediction;
    double avg = Arrays.stream(train).average().orElse(0.0);
    Arrays.fill(prediction, avg);
    return prediction;
  }

  public static double[] fourierExtrapolationPredict(int[] train, int horizon, int harmonics) {
    // train = np.array(train)
    if (horizon <= 0) return new double[0];
    if (train == null || train.length == 0) {
        double[] out = new double[horizon];
        Arrays.fill(out, 0.0);
        return out;
    }

    // n = train.size
    final int n = train.length;
    // n_harm = harmonics
    final int nHarm = Math.max(0, harmonics);

    // p = np.polyfit(t, train, 1)
    double sumX = 0.0, sumY = 0.0, sumXY = 0.0, sumX2 = 0.0;
    for (int i = 0; i < n; i++) {
        sumX += i;
        sumY += train[i];
        sumXY += i * train[i];
        sumX2 += (double) i * i;
    }
    double denom = n * sumX2 - sumX * sumX;
    double slope = (denom == 0.0) ? 0.0 : (n * sumXY - sumX * sumY) / denom;

    // x_notrend = train - p[0] * t
    double[] xNotrend = new double[n];
    for (int i = 0; i < n; i++) {
        xNotrend[i] = train[i] - slope * i;
    }

    // x_freqdom = fft.fft(x_notrend)
    double[] complex = new double[2 * n];
    for (int i = 0; i < n; i++) {
        complex[2 * i] = xNotrend[i];
        complex[2 * i + 1] = 0.0;
    }

    DoubleFFT_1D fft = new DoubleFFT_1D(n);
    fft.complexForward(complex); // in-place: complex[2*k] = Re, complex[2*k+1] = Im

    // f = fft.fftfreq(n)
    double[] freqs = new double[n];
    for (int k = 0; k < n; k++) {
        freqs[k] = (k <= n / 2) ? ((double) k / n) : ((double) (k - n) / n);
    }

    // indexes = list(range(n))
    Integer[] idx = IntStream.range(0, n).boxed().toArray(Integer[]::new);
    // indexes.sort(key = lambda i: np.absolute(f[i]))
    Arrays.sort(idx, Comparator.comparingDouble(i -> Math.abs(freqs[i])));

    // t = np.arange(0, n + horizon)
    final int totalLen = n + horizon;
    // restored_sig = np.zeros(t.size)
    double[] restored = new double[totalLen];
    Arrays.fill(restored, 0.0);

    // for i in indexes[:1 + n_harm * 2]:
    int components = Math.min(n, 1 + nHarm * 2);
    for (int j = 0; j < components; j++) {
        int i = idx[j];

        // ampli = np.absolute(x_freqdom[i]) / n
        double re = complex[2 * i];
        double im = complex[2 * i + 1];
        double amplitude = Math.hypot(re, im) / n;

        // phase = np.angle(x_freqdom[i])
        double phase = Math.atan2(im, re);

        // restored_sig += ampli * cos(2*pi*f[i]*t + phase)
        double freq = freqs[i];
        double twoPiFreq = 2.0 * Math.PI * freq;

        for (int t = 0; t < totalLen; t++) {
            restored[t] += amplitude * Math.cos(twoPiFreq * t + phase);
        }
    }

    // restored_sig + p[0] * t
    double[] fullSignal = new double[totalLen];
    for (int t = 0; t < totalLen; t++) {
        fullSignal[t] = restored[t] + slope * t;
    }

    double[] forecastOnly = new double[horizon];
    for (int i = 0; i < horizon; i++) {
      int t = n + i; // Index on the full timeline
      double value = restored[t] + (slope * t);
      forecastOnly[i] = Math.max(0.0, value);
    }
    return forecastOnly;
  }

  public static double[] fourierExtrapolationPredict(int[] train, int horizon) {
    return fourierExtrapolationPredict(train, horizon, HARMONICS);
  }

    // --- quick test ---
  public static void main(String[] args) {
    int[] train = new int[] { 1, 2, 3, 4, 5, 6, 7 };
    int horizon = 5;

    double[] meanForecast = meanPredict(train, horizon);
    System.out.println("Mean forecast: " + Arrays.toString(meanForecast));

    double[] fourierForecast = fourierExtrapolationPredict(train, horizon, 10);
    System.out.println("Fourier forecast: " + Arrays.toString(fourierForecast));
  }

}
