package org.example.tunesfx.controller;

import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.PauseTransition;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Spinner;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import javafx.util.Duration;
import org.example.tunesfx.audio.Sample;
import org.example.tunesfx.audio.SampleBank;
import org.example.tunesfx.audio.SamplePlayer;
import org.example.tunesfx.audio.StepData;
import org.example.tunesfx.utils.AudioFileLoader;
import org.example.tunesfx.utils.GlobalState;

import javax.sound.sampled.UnsupportedAudioFileException;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class ChannelRackController {

    @FXML private Button btnEncenderRitmo;
    @FXML private Button btnNuevaPista;
    @FXML private VBox rackContainer;
    @FXML private Spinner<Integer> spinnerBPM;

    private Timeline sequencerTimeline;
    private boolean isPlaying = false;
    private static double referenciaBPM = 120.0; 

    private static final int NUM_STEPS = ChannelRackRowController.NUM_STEPS;
    private int currentStep = -1;
    private List<ChannelRackRowController> allRows = new ArrayList<>();
    private java.util.Map<ChannelRackRowController, List<PauseTransition>> activeSchedulers = new java.util.HashMap<>();

    @FXML
    public void initialize() {
        GlobalState.setChannelRackController(this);

        GlobalState.bpmProperty().addListener((obs, oldVal, newVal) -> {
            updateSequencerRate(newVal.doubleValue());
        });

        initializeSequencer();
        SampleBank.getInstance().setOnSampleSaved(this::addNewRow);
    }

    @FXML
    private void addSample(ActionEvent event) {
        loadExternalSound();
    }

    private void loadExternalSound() {
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Load sample (WAV)");
        fileChooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Archivos de Audio WAV", "*.wav"));

        Stage stage = (Stage) btnNuevaPista.getScene().getWindow();
        File file = fileChooser.showOpenDialog(stage);

        if (file != null) {
            try {
                short[] data = AudioFileLoader.loadSample(file);
                Sample externalSample = new Sample(data);
                addNewRow(externalSample);
            } catch (IOException | UnsupportedAudioFileException e) {
                e.printStackTrace();
            }
        }
    }

    private void initializeSequencer() {
        double currentBpm = GlobalState.getBpm();
        double beatDurationMillis = 60000.0 / 120.0; 
        double stepDurationMillis = beatDurationMillis / 4.0;

        sequencerTimeline = new Timeline();
        sequencerTimeline.setCycleCount(Animation.INDEFINITE);

        KeyFrame keyFrame = new KeyFrame(Duration.millis(stepDurationMillis), e -> runSequencerStep());
        sequencerTimeline.getKeyFrames().add(keyFrame);

        updateSequencerRate(currentBpm);
    }

    private void updateSequencerRate(double newBPM) {
        if (sequencerTimeline == null) return;
        double newRate = newBPM / 120.0;
        sequencerTimeline.setRate(newRate);
    }

    public void addNewRow(Sample sample) {
        Platform.runLater(() -> {
            if (sample == null) return;
            try {
                FXMLLoader loader = new FXMLLoader(getClass().getResource("/org/example/tunesfx/ChannelRackRow.fxml"));
                HBox newRowNode = loader.load();
                ChannelRackRowController rowController = loader.getController();
                rowController.setSample(sample);
                rowController.setOnDelete(() -> {
                    allRows.remove(rowController);
                    rackContainer.getChildren().remove(newRowNode);
                });
                allRows.add(rowController);
                rackContainer.getChildren().add(newRowNode);
            } catch (IOException e) {
                e.printStackTrace();
            }
        });
    }

    private void runSequencerStep() {
        int maxActiveStep = -1;
        for (ChannelRackRowController row : allRows) {
            int rowLastStep = row.getLastActiveStepIndex();
            if (rowLastStep > maxActiveStep) maxActiveStep = rowLastStep;
        }

        int loopLimit = (maxActiveStep == -1) ? 15 : maxActiveStep;
        if (maxActiveStep != -1) {
            int remainder = (loopLimit + 1) % 4;
            if (remainder != 0) loopLimit += (4 - remainder);
        }

        int prevStep = currentStep;
        if (currentStep >= loopLimit) currentStep = 0;
        else currentStep++;

        double currentBPM = GlobalState.getBpm();
        double stepDuration = (60000.0 / currentBPM) / 4.0;

        for (ChannelRackRowController row : allRows) {
            if (prevStep != -1) row.clearPlayhead(prevStep);
            if (currentStep == 0 && prevStep > 0) row.clearPlayhead(prevStep);

            row.setPlayhead(currentStep);

            StepData stepData = row.getStepData(currentStep);
            if (stepData != null && stepData.isActive()) {
                Sample sampleToPlay = row.getSample();
                if (sampleToPlay != null) {
                    SamplePlayer.playStep(sampleToPlay, stepData, stepDuration);
                }
            }
        }
    }

    @FXML
    private void handlePlayRitmo(ActionEvent event) {
        if (isPlaying) {
            stopSequencer();
            btnEncenderRitmo.setStyle("-fx-background-color: #424242");
        }
        else {
            if (allRows.isEmpty()) return;
            sequencerTimeline.play();
            isPlaying = true;
            btnEncenderRitmo.setStyle("-fx-background-color: #797979");
        }
    }

    private void stopSequencer() {
        sequencerTimeline.stop();
        isPlaying = false;
        for (ChannelRackRowController row : allRows) {
            if (currentStep != -1) row.clearPlayhead(currentStep);
        }
        currentStep = -1;
    }

    // Busca una fila por nombre y reproduce su sample (como un One-Shot)
    public void playSoundByName(String rowName) {
        for (ChannelRackRowController row : allRows) {

            if (row.getTrackName().equals(rowName)) {
                schedulePatternPlayback(row);
                return;
            }
        }
        System.out.println("No se encontró la pista: " + rowName);
    }

    // Busca una fila por nombre y detiene su reproducción
    public void stopSoundByName(String rowName) {
        for (ChannelRackRowController row : allRows) {
            if (row.getTrackName().equals(rowName)) {
                stopPatternPlayback(row);
                return;
            }
        }
    }

    private void schedulePatternPlayback(ChannelRackRowController row) {
        double bpm = GlobalState.getBpm();
        double stepDurationMillis = (60000.0 / bpm) / 4.0;
        Sample sample = row.getSample();
        if (sample == null) return;

        List<PauseTransition> rowTransitions = new ArrayList<>();
        activeSchedulers.put(row, rowTransitions);

        for (int i = 0; i < row.getStepCount(); i++) {
            StepData stepData = row.getCombinedStepData(i);
            if (stepData != null && stepData.isActive()) {
                double delay = i * stepDurationMillis;
                PauseTransition scheduler = new PauseTransition(Duration.millis(delay));

                rowTransitions.add(scheduler); // <--- REGISTRAMOS

                final StepData finalStepData = stepData;
                scheduler.setOnFinished(e -> {
                    SamplePlayer.playStep(sample, finalStepData, stepDurationMillis);
                    rowTransitions.remove(scheduler); // Limpieza al terminar
                });
                scheduler.play();
            }
        }
    }

    private void stopPatternPlayback(ChannelRackRowController row) {
        List<PauseTransition> transitions = activeSchedulers.get(row);
        if (transitions != null) {
            for (PauseTransition pt : transitions) {
                pt.stop();
            }
            transitions.clear();
            activeSchedulers.remove(row);
        }


        SamplePlayer.stopSample(row.getSample());
    }

    public void addTrackFromLibrary(String name, File audioFile) {
        FXMLLoader loader = new FXMLLoader(getClass().getResource("/org/example/tunesfx/ChannelRackRow.fxml")); // Asegúrate de que la ruta del FXML es correcta, a veces es views/ChannelRackRow.fxml
        try {
            Parent rowNode = loader.load();
            ChannelRackRowController rowController = loader.getController();

            // 1. Configurar nombre
            rowController.setTrackName(name);

            // 2. Cargar el sonido
            rowController.loadSample(audioFile);

            // 3. Añadir al contenedor visual
            rackContainer.getChildren().add(rowNode);

            // 4. Registrar controlador
            rowController.setOnDelete(() -> {
                allRows.remove(rowController);
                rackContainer.getChildren().remove(rowNode);
            });
            allRows.add(rowController);

        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public void shutdown() {
        stopSequencer();
    }

    public ChannelRackRowController findRowController(String name) {
        for (ChannelRackRowController row : allRows) {
            if (row.getTrackName().equals(name)) {
                return row;
            }
        }
        return null;
    }
}