package org.example.tunesfx.synth;

import org.example.tunesfx.audio.Audio;
import org.example.tunesfx.utils.Utils;

import java.util.HashMap;

public class Sintetizador {

    private static final HashMap<Character,Double> KEY_FREQUENCIES = new HashMap<>();
    private boolean shouldGenerate;
    private static final int NUM_OSCILLATORS = 5;

    // Componentes
    private Oscilator[] oscillators;
    private WaveViewer waveViewer;
    private Filter filtro;
    private LFO lfo;
    private ADSR adsr = new ADSR();
    private boolean noteIsPressed = false;

    // Estados
    private boolean filtroActivado = false;
    private boolean lfoActivado = false;

    // Variables para Modulación Estable
    private double baseFilterCutoff = 1000.0;
    private double baseFilterResonance = 0.5;
    private double lfoVolumeGain = 1.0; // Multiplicador de volumen (1.0 = normal)
    private double currentBaseFrequency = 440.0;

    // Rangos de Modulación ---
    private static final double LFO_CUTOFF_OCTAVES = 2.0; // ±2 octavas
    private static final double LFO_RESONANCE_RANGE = 0.5;
    private static final double LFO_VOLUME_RANGE = 0.8; // Modula el volumen un 80%
    private static final double LFO_PITCH_RANGE = 12.0; // Modulación máxima de Pitch

    // Suavizado de parámetros 

    private static final double PARAM_SMOOTHING = 0.3;
    private double smoothedCutoff = 1000.0;
    private double smoothedResonance = 0.5;

    private int controlCounter = 0;
    private static final int CONTROL_RATE_SAMPLES = 64;

    private Runnable updateCallback;
    // Envolvente de Filtro
    private ADSR filterAdsr = new ADSR();
    private double filterEnvelopeAmount = 0.0; // En Hz (Ej: de -10000 a +10000)

    public ADSR getFilterAdsr() { return filterAdsr; }
    public void setFilterEnvelopeAmount(double amount) { this.filterEnvelopeAmount = amount; }
    public double getFilterEnvelopeAmount() { return filterEnvelopeAmount; }

    // Hilo de Audio
    private final Audio hiloAudio = new Audio(() -> {
        if (!noteIsPressed && adsr.isIdle()) {
            return null;
        }
        short[] s = new short[Audio.BUFFER_SIZE];
        for (int i = 0; i < Audio.BUFFER_SIZE; i++) {
            double d = nextSample();
            s[i] = (short) (Short.MAX_VALUE * d);
        }
        return s;
    });

    static {
        final char[] PIANO_KEYS = "-<zsxdcvgbhnjmq2w3er5t6y7ui9o0p".toCharArray();
        final int STARTING_NOTE = 46;
        for (int i = 0; i < PIANO_KEYS.length; i++) {
            int currentNote = STARTING_NOTE + i;
            KEY_FREQUENCIES.put(PIANO_KEYS[i], Utils.getKeyFrequency(currentNote));
        }
    }

    public Sintetizador(Oscilator[] oscillators, WaveViewer waveViewer) {
        this.oscillators = oscillators;
        this.waveViewer = waveViewer;

        this.filtro = new Filter();
        this.lfo = new LFO();

        this.waveViewer.setOscillators(this.oscillators);
    }

    public void updateWaveviewer() {
        if (updateCallback != null) {
            updateCallback.run();
        }
    }

    public void setUpdateCallback(Runnable callback) {
        this.updateCallback = callback;
    }

    // LÓGICA DE AUDIO PRINCIPAL

    public void setFrequency(double frequency) {
        for (Oscilator osc : oscillators) {
            osc.setKeyFrequency(frequency);
        }
    }

    public double nextSample() {
        double ampEnv = adsr.getNextEnvelope();
        double filterEnv = filterAdsr.getNextEnvelope();

        if (controlCounter++ >= CONTROL_RATE_SAMPLES) {
            controlCounter = 0;

            double lfoModValue = 0.0;

            // Procesar LFO
            boolean lfoActive = lfoActivado && lfo.getTarget() != LFO.Target.NONE;
            if (lfoActive) {
                lfoModValue = lfo.getModulationValue(CONTROL_RATE_SAMPLES);

                switch (lfo.getTarget()) {
                    case OSC_VOLUME:
                        double volMod = lfoModValue * LFO_VOLUME_RANGE;
                        lfoVolumeGain = Math.max(0.0, Math.min(1.0, 1.0 - Math.abs(volMod)));
                        break;
                    case PITCH:
                        double pitchMod = lfoModValue * LFO_PITCH_RANGE;
                        double pitchMultiplier = Math.pow(2.0, pitchMod / 12.0);
                        for (Oscilator osc : oscillators) {
                            osc.setKeyFrequency(currentBaseFrequency * pitchMultiplier);
                        }
                        break;
                    default: break; 
                }
            } else {
                lfoVolumeGain = 1.0;
            }

            double lfoOctaves = 0.0;
            if (lfoActive && lfo.getTarget() == LFO.Target.FILTER_CUTOFF) {
                lfoOctaves = lfoModValue * LFO_CUTOFF_OCTAVES;
            }
            double finalCutoff = (baseFilterCutoff + (filterEnv * filterEnvelopeAmount))
                    * Math.pow(2.0, lfoOctaves);
            finalCutoff = Math.max(20.0, Math.min(20000.0, finalCutoff));
            smoothedCutoff += (finalCutoff - smoothedCutoff) * PARAM_SMOOTHING;
            filtro.setFrecuenciaCorte(smoothedCutoff);

            // RESONANCIA: modulación + suavizado
            double targetResonance = baseFilterResonance;
            if (lfoActive && lfo.getTarget() == LFO.Target.FILTER_RESONANCE) {
                targetResonance += lfoModValue * LFO_RESONANCE_RANGE;
            }
            targetResonance = Math.max(0.1, Math.min(1.0, targetResonance));
            smoothedResonance += (targetResonance - smoothedResonance) * PARAM_SMOOTHING;
            filtro.setResonancia(smoothedResonance);
        }
        // 3. Mezcla y procesamiento de audio
        double totalSample = 0;
        for (Oscilator osc : oscillators) {
            totalSample += osc.getNextSample();
        }
        double muestraMezclada = totalSample / NUM_OSCILLATORS;
        muestraMezclada *= ampEnv;
        if (lfoActivado && lfo.getTarget() == LFO.Target.OSC_VOLUME) {
            muestraMezclada *= lfoVolumeGain;
        }

        if (filtroActivado) {
            muestraMezclada = filtro.procesar(muestraMezclada);
        }

        if (!Double.isFinite(muestraMezclada)) {
            filtro.reset();
            return 0.0;
        }
        return Math.max(-1.0, Math.min(1.0, muestraMezclada));
    }

    public short[] generateSample(int numSamples) {
        for (Oscilator osc : oscillators) osc.resetPhase();
        filtro.reset();
        lfo.reset();

        adsr.noteOn();
        filterAdsr.noteOn(); 

        short[] s = new short[numSamples];
        for (int i = 0; i < numSamples; i++) {
            double d = nextSample();
            s[i] = (short) (Short.MAX_VALUE * d);
        }
        return s;
    }

    // MANEJO DE TECLADO

    public void onKeyPressed(char keyChar) {
        if (!KEY_FREQUENCIES.containsKey(keyChar)) return;
        currentBaseFrequency = KEY_FREQUENCIES.get(keyChar);
        setFrequency(currentBaseFrequency);
        noteIsPressed = true;

        adsr.noteOn();
        filterAdsr.noteOn();

        if (hiloAudio.isInitialized() && !hiloAudio.isRunning()) {
            hiloAudio.triggerPlayBack();
        }
    }

    public void onKeyReleased() {
        noteIsPressed = false;
        adsr.noteOff();
        filterAdsr.noteOff(); 
    }

    public ADSR getAdsr() { return adsr; }


    public Oscilator[] getOscillatorsFX() { return oscillators; }
    public WaveViewer getWaveViewerFX() { return waveViewer; }

    // FILTRO
    public void setFiltroActivado(boolean activado) {
        this.filtroActivado = activado;

        filtro.reset();
    }
    public boolean isFiltroActivado() { return filtroActivado; }

    public void setTipoFiltro(Filter.Tipo tipo) {
        filtro.setTipo(tipo);
        filtro.reset();
    }
    public Filter.Tipo getTipoFiltro() { return filtro.getTipo(); }

    public void setFrecuenciaCorteFiltro(double frecuencia) {
        this.baseFilterCutoff = frecuencia;
        if (!lfoActivado || lfo.getTarget() != LFO.Target.FILTER_CUTOFF) {
            smoothedCutoff = Math.max(20.0, Math.min(20000.0, frecuencia));
            filtro.setFrecuenciaCorte(smoothedCutoff);
        }
    }
    public double getFrecuenciaCorteFiltro() { return baseFilterCutoff; } 

    public void setResonanciaFiltro(double resonancia) {
        this.baseFilterResonance = resonancia; // Guardamos la base
        if (!lfoActivado || lfo.getTarget() != LFO.Target.FILTER_RESONANCE) {
            smoothedResonance = Math.max(0.1, Math.min(1.0, resonancia));
            filtro.setResonancia(smoothedResonance);
        }
    }
    public double getResonanciaFiltro() { return baseFilterResonance; } 

    // LFO
    public void setLFOActivado(boolean activado) {
        this.lfoActivado = activado;
        if (!activado) {
            lfo.reset();

            lfoVolumeGain = 1.0;

            setFrequency(currentBaseFrequency);
        }
    }

    public boolean isLFOActivado() { return lfoActivado; }

    public void setLFWaveform(LFO.Waveform waveform) { lfo.setWaveform(waveform); }
    public LFO.Waveform getLFWaveform() { return lfo.getWaveform(); }

    public void setLFORate(double rate) { lfo.setRate(rate); }
    public double getLFORate() { return lfo.getRate(); }

    public void setLFOAmount(double amount) { lfo.setAmount(amount); }
    public double getLFOAmount() { return lfo.getAmount(); }

    public void setLFOTarget(LFO.Target target) {

        if (lfoActivado) {
            lfoVolumeGain = 1.0;

            setFrequency(currentBaseFrequency);
        }
        lfo.setTarget(target);
    }

    public LFO.Target getLFOTarget() { return lfo.getTarget(); }

    // Info interna
    public static class AudioInfo {
        public static final int SAMPLE_RATE = 44100;
    }
}
