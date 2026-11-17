package org.example.tunesfx.controller;

import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Line;
import javafx.stage.DirectoryChooser;
import javafx.stage.Stage;
import javafx.util.Duration;
import org.example.tunesfx.audio.AudioCoordinator;
import org.example.tunesfx.audio.AudioSettingsDialog;
import org.example.tunesfx.audio.PlaylistItem;
import org.example.tunesfx.utils.AudioExporter;
import org.example.tunesfx.utils.GlobalState;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.prefs.Preferences;

public class PrincipalController {
    @FXML private Button btnAjustes;
    @FXML private Button btnAddToLibrary;
    @FXML private TreeView libraryTreeView;
    @FXML private Spinner bpmSpinner;
    @FXML private Button btnStopSong;
    @FXML private Button btnPlaySong;
    @FXML private GridPane playlistGrid;
    @FXML private ListView patternListView; // lista de patterns
    @FXML private ScrollPane playlistScrollPane;
    @FXML private Button openChannelRackButton;
    @FXML private Button btnSalir;
    @FXML private Button openSynthButton;
    @FXML private HBox timelineHeader;
    @FXML private VBox trackHeadersContainer;
    @FXML private Pane playlistGridContent;

    private Stage rackStage;
    private static final File SAMPLES_DIRECTORY = new File("src/main/resources/samples");
    private static final Preferences LIBRARY_PREFERENCES =
            Preferences.userNodeForPackage(PrincipalController.class);
    private static final String PREF_LIBRARY_FOLDERS = "libraryFolders";
    private final List<File> externalLibraryFolders = new ArrayList<>();
    private List<PlaylistItem> songData = new ArrayList<>();
    private Line playheadLine; // La línea visual del cursor
    private Timeline songTimeline; // El "reloj" maestro de la Playlist
    private double currentPlayheadX = 0; // Posición X actual del cursor
    private double previousPlayheadX = 0; // Para recordar dónde estábamos antes

    // Variables para el diseño de la rejilla
    private final int CELL_WIDTH = 40;   // Ancho de cada celda de tiempo (1 compás o beat)
    private final int TRACK_HEIGHT = 40; // Alto de cada pista
    private final int NUM_TRACKS = 40;   // Pistas iniciales
    private final int NUM_BARS = 400;    // Compases iniciales

    // Variables para la gestión del ratón en la Playlist
    private double mouseAnchorX;
    private double mouseAnchorY;
    private double initialLayoutX;
    private double initialLayoutY;
    private boolean isResizing = false;
    private final double RESIZE_MARGIN = 10.0; 
    // Detección de click vs drag en la rejilla de la Playlist
    private double gridPressedX;
    private double gridPressedY;
    private boolean gridPressed = false;
    private static final double DRAG_TOLERANCE_PX = 5.0;
    private long lastBlockCreationTime = 0;
    private int lastBlockCol = -1;
    private int lastBlockRow = -1;

    @FXML
    public void initialize() {
        GlobalState.setPrincipalController(this);
        SpinnerValueFactory<Double> bpmFactory =
                new SpinnerValueFactory.DoubleSpinnerValueFactory(30.0, 300.0, 120.0, 1.0);
        bpmSpinner.setValueFactory(bpmFactory);
        bpmSpinner.valueProperty().addListener((obs, oldVal, newVal) -> {
            GlobalState.setBpm((Double) newVal);
        });

        // Shortcuts del teclado
        Platform.runLater(() -> {
            Scene scene = openSynthButton.getScene();
            // Atajo: CTRL + S para el Sintetizador
            scene.getAccelerators().put(
                    new KeyCodeCombination(KeyCode.S, KeyCombination.CONTROL_ANY),
                    () -> handleOpenSynth(null)
            );
            // Atajo: CTRL + R para el Channel Rack
            scene.getAccelerators().put(
                    new KeyCodeCombination(KeyCode.C, KeyCombination.CONTROL_ANY),
                    () -> handleOpenChannelRack(null)
            );
        });
        // Acción para el botón de cambiar dispositivo
        btnAjustes.setOnAction(event -> {
            Stage stage = (Stage) btnAjustes.getScene().getWindow();
            String chosen = AudioSettingsDialog.showDialog(stage);
            if (chosen != null && !chosen.isEmpty()) {
                AudioCoordinator.switchToDevice(chosen);
            }
        });
        setupPlaylist();
        enablePatternPainting();
        initializeSongTimeline();
        setupPatternListViewContextMenu();
        setupLibrary();
    }

    @FXML
    private void handleOpenSynth(ActionEvent event) {
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/org/example/tunesfx/SintetizadorView.fxml"));
            Pane synthRoot = loader.load();
            SintetizadorController synthController = loader.getController();

            Stage synthStage = new Stage();
            setWindowIcon(synthStage);
            Scene synthScene = new Scene(synthRoot);
//            synthStage.setAlwaysOnTop(true);

            synthScene.setOnKeyPressed(synthController::handleKeyPressed);
            synthScene.setOnKeyReleased(synthController::handleKeyReleased);

            synthStage.setTitle("Synth");
//            synthStage.initStyle(StageStyle.UNDECORATED);
            synthStage.setScene(synthScene);
            synthStage.setResizable(false);
            synthStage.setX(1070);
            synthStage.setY(285);
            synthStage.show();

        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    @FXML
    public void handleOpenChannelRack(ActionEvent actionEvent) {
        if (rackStage == null) {
            try {
                FXMLLoader loader = new FXMLLoader(getClass().getResource("/org/example/tunesfx/ChannelRackView.fxml"));
                Pane rackRoot = loader.load();
                ChannelRackController rackController = loader.getController();

                rackStage = new Stage();
                setWindowIcon(rackStage);
                Scene rackScene = new Scene(rackRoot);
                rackStage.setAlwaysOnTop(true);


                String css = this.getClass().getResource("/org/example/tunesfx/styles.css").toExternalForm();
                rackScene.getStylesheets().add(css);

                rackStage.setOnCloseRequest(e -> rackController.shutdown());

                rackStage.setTitle("Channel Rack");
//                rackStage.initStyle(StageStyle.UNDECORATED);
                rackStage.setScene(rackScene);
                rackStage.setResizable(false);
                rackStage.setY(150);
                rackStage.setX(270);

                rackStage.setOnCloseRequest(e -> {
                    rackController.shutdown();
                    rackStage = null;
                });
                rackStage.show();

            } catch (IOException e) {
                e.printStackTrace();
            } catch (NullPointerException e) {
                System.err.println("No se pudo encontrar el archivo CSS. Revisa el nombre.");
                e.printStackTrace();
            }
        } else {
            rackStage.toFront();
            rackStage.requestFocus();
        }
    }

    @FXML
    private void handlePlaySong() {
        if (songTimeline == null) initializeSongTimeline();

        if (songTimeline.getStatus() == Animation.Status.RUNNING) {
            songTimeline.pause();
        } else {
            songTimeline.play();
        }
    }

    @FXML
    private void handleStopSong() {
        if (songTimeline != null) {
            songTimeline.stop();
            currentPlayheadX = 0;
            previousPlayheadX = 0;
            playheadLine.setStartX(0);
            playheadLine.setEndX(0);
            btnPlaySong.setStyle("-fx-background-color: #424242;");
        }
    }

    public void addPatternFromSample(String patternName) {
        if (!patternListView.getItems().contains(patternName)) {
            patternListView.getItems().add(patternName);
        }
    }

    private void setupPlaylist() {
        timelineHeader.getChildren().clear();
        trackHeadersContainer.getChildren().clear();
        playlistGridContent.getChildren().clear();
        double totalWidth = NUM_BARS * CELL_WIDTH;
        double totalHeight = NUM_TRACKS * TRACK_HEIGHT;
        playlistGridContent.setMinWidth(totalWidth);
        playlistGridContent.setPrefSize(totalWidth, totalHeight);
        // --- 1. GENERAR TIMELINE (Eje X) ---
        for (int i = 1; i <= NUM_BARS; i++) {
            Label barLabel = new Label(String.valueOf(i));
            barLabel.setPrefWidth(CELL_WIDTH);
            barLabel.setMinWidth(CELL_WIDTH);
            barLabel.setAlignment(Pos.CENTER);
            barLabel.setStyle("-fx-text-fill: #888888; -fx-font-size: 10px; -fx-border-color: #333333; -fx-border-width: 0 1 0 0;");
            timelineHeader.getChildren().add(barLabel);
            Line vLine = new Line(i * CELL_WIDTH, 0, i * CELL_WIDTH, totalHeight);
            vLine.setStroke(Color.web("#2A2A2A")); 
            vLine.getStrokeDashArray().addAll(2d, 2d); 
            playlistGridContent.getChildren().add(vLine);
        }

        // --- 2. GENERAR TRACK HEADERS (Eje Y) ---
        for (int j = 0; j < NUM_TRACKS; j++) {
            Label trackLabel = new Label("Track " + (j + 1));
            trackLabel.setPrefHeight(TRACK_HEIGHT);
            trackLabel.setMinHeight(TRACK_HEIGHT);
            trackLabel.setMaxWidth(Double.MAX_VALUE);
            trackLabel.setPadding(new Insets(0, 0, 0, 10)); 
            trackLabel.setAlignment(Pos.CENTER_LEFT);
            String bg = (j % 2 == 0) ? "#262626" : "#232323";
            trackLabel.setStyle("-fx-text-fill: #aaaaaa; -fx-background-color: " + bg + "; -fx-border-color: #1A1A1A; -fx-border-width: 0 0 1 0;");
            // Menú contextual para el track
            ContextMenu trackMenu = new ContextMenu();
            MenuItem renameItem = new MenuItem("Rename track");

            renameItem.setOnAction(e -> {
                TextInputDialog dialog = new TextInputDialog(trackLabel.getText());
                dialog.setTitle("Rename");
                dialog.setHeaderText(null);
                dialog.setContentText("New name:");
                dialog.setGraphic(null);
                try {
                    DialogPane dialogPane = dialog.getDialogPane();
                    String css = this.getClass().getResource("styles.css").toExternalForm();
                    dialogPane.getStylesheets().add(css);
                    dialogPane.getStyleClass().add("my-dialog");
                } catch (Exception ex) {
                }
                dialog.showAndWait().ifPresent(newName -> {
                    if (!newName.trim().isEmpty()) {
                        trackLabel.setText(newName);
                    }
                });
            });

            trackMenu.getItems().add(renameItem);
            trackLabel.setContextMenu(trackMenu);
            trackLabel.setOnMouseClicked(e -> {
                if (e.getClickCount() == 2 && e.getButton() == javafx.scene.input.MouseButton.PRIMARY) {
                    renameItem.fire();
                }
            });
            trackHeadersContainer.getChildren().add(trackLabel);
            Line hLine = new Line(0, (j + 1) * TRACK_HEIGHT, totalWidth, (j + 1) * TRACK_HEIGHT);
            hLine.setStroke(Color.web("#1A1A1A")); 

            playlistGridContent.getChildren().add(hLine);
        }
        playheadLine = new Line(0, 0, 0, totalHeight);
        playheadLine.setStroke(Color.RED); 
        playheadLine.setStrokeWidth(2);
        playheadLine.setOpacity(0.8);
        playheadLine.setMouseTransparent(true); 

        playlistGridContent.getChildren().add(playheadLine);
    }

    private void enablePatternPainting() {
        playlistGridContent.setOnMousePressed(event -> {
            if (event.getButton() != javafx.scene.input.MouseButton.PRIMARY) return;
            gridPressedX = event.getX();
            gridPressedY = event.getY();
            gridPressed = true;
        });
        playlistGridContent.setOnMouseReleased(event -> {
            if (event.getButton() != javafx.scene.input.MouseButton.PRIMARY) return;
            if (!gridPressed) return;
            double dx = Math.abs(event.getX() - gridPressedX);
            double dy = Math.abs(event.getY() - gridPressedY);
            if (dx < DRAG_TOLERANCE_PX && dy < DRAG_TOLERANCE_PX) {
                String selectedPattern = (String) patternListView.getSelectionModel().getSelectedItem();
                if (selectedPattern != null) {
                    int colIndex = (int) (gridPressedX / (CELL_WIDTH * 4));
                    int rowIndex = (int) (gridPressedY / TRACK_HEIGHT);
                    long now = System.currentTimeMillis();
                    if (colIndex == lastBlockCol && rowIndex == lastBlockRow
                            && (now - lastBlockCreationTime) < 150) {
                        gridPressed = false;
                        return;
                    }
                    lastBlockCol = colIndex;
                    lastBlockRow = rowIndex;
                    lastBlockCreationTime = now;
                    double snapX = colIndex * (CELL_WIDTH * 4);
                    double snapY = rowIndex * TRACK_HEIGHT;
                    createClip(selectedPattern, snapX, snapY);
                }
            }
            gridPressed = false;
        });
    }

    private void createClip(String patternName, double x, double y) {
        int durationBars = 1;

        if (GlobalState.getChannelRackController() != null) {
            ChannelRackRowController row = GlobalState.getChannelRackController().findRowController(patternName);
            if (row != null) {
                int lastStep = row.getLastActiveStepIndex();
                if (lastStep >= 0) {
                    durationBars = (lastStep / 16) + 1;
                }
            }
        }

        double initialWidth = durationBars * (CELL_WIDTH * 4);

        int colIndex = (int) (x / (CELL_WIDTH * 4));
        double snapX = colIndex * (CELL_WIDTH * 4);

        int trackIndex = (int) (y / TRACK_HEIGHT);
        PlaylistItem item = new PlaylistItem(patternName, colIndex + 1, trackIndex);
        item.setDurationBars(durationBars);
        songData.add(item);

        javafx.scene.layout.StackPane clipContainer = new javafx.scene.layout.StackPane();

        clipContainer.setMinWidth(initialWidth);
        clipContainer.setPrefWidth(initialWidth);
        clipContainer.setMaxWidth(initialWidth);

        clipContainer.setPrefHeight(TRACK_HEIGHT);
        clipContainer.setLayoutX(snapX); 
        clipContainer.setLayoutY(y);
        clipContainer.setUserData(item);

        clipContainer.setStyle("-fx-background-color: #4a4a4a; -fx-background-radius: 4; -fx-border-color: #666666; -fx-border-width: 1;");
        Label nameLabel = new Label(patternName);
        nameLabel.setStyle("-fx-text-fill: white; -fx-font-size: 10px; -fx-font-weight: bold;");
        clipContainer.getChildren().add(nameLabel);

        // LÓGICA DE INTERACCIÓN (MOUSE EVENTS)

        // --- 1. MOUSE MOVED (Cambiar cursor) ---
        clipContainer.setOnMouseMoved(e -> {
            if (e.getX() > clipContainer.getWidth() - RESIZE_MARGIN) {
                clipContainer.setCursor(javafx.scene.Cursor.H_RESIZE);
            } else {
                clipContainer.setCursor(javafx.scene.Cursor.HAND);
            }
        });
        // --- 2. MOUSE PRESSED (Iniciar acción) ---
        clipContainer.setOnMousePressed(e -> {
            if (e.getButton() == javafx.scene.input.MouseButton.PRIMARY) {
                mouseAnchorX = e.getSceneX();
                mouseAnchorY = e.getSceneY();
                initialLayoutX = clipContainer.getLayoutX();
                initialLayoutY = clipContainer.getLayoutY();
                isResizing = (e.getX() > clipContainer.getWidth() - RESIZE_MARGIN);
                clipContainer.toFront();
                e.consume();
            }
        });
        // --- 3. MOUSE DRAGGED (Arrastrar) ---
        clipContainer.setOnMouseDragged(e -> {
            if (e.getButton() == javafx.scene.input.MouseButton.PRIMARY) {
                if (isResizing) {
                    // MODO REDIMENSIONAR
                    double newWidth = e.getX();
                    clipContainer.setMaxWidth(newWidth);
                    if (newWidth < CELL_WIDTH) newWidth = CELL_WIDTH;
                    clipContainer.setPrefWidth(newWidth);
                } else {
                    // MODO MOVER
                    double dragX = e.getSceneX() - mouseAnchorX;
                    double dragY = e.getSceneY() - mouseAnchorY;

                    clipContainer.setLayoutX(initialLayoutX + dragX);
                    clipContainer.setLayoutY(initialLayoutY + dragY);
                }
                e.consume();
            }
        });
        // --- 4. MOUSE RELEASED (Soltar y Ajustar a Rejilla - SNAP) ---
        clipContainer.setOnMouseReleased(e -> {
            if (e.getButton() == javafx.scene.input.MouseButton.PRIMARY) {
                double barWidth = CELL_WIDTH * 4;

                if (isResizing) {
                    double currentWidth = clipContainer.getPrefWidth();

                    int numBeats = (int) Math.round(currentWidth / CELL_WIDTH);
                    if (numBeats < 1) numBeats = 1; 

                    double snappedWidth = numBeats * CELL_WIDTH;
                    clipContainer.setPrefWidth(snappedWidth);
                    clipContainer.setMinWidth(snappedWidth);
                    clipContainer.setMaxWidth(snappedWidth);

                    item.setDurationBars(numBeats / 4.0);

                } else {
                    double rawX = clipContainer.getLayoutX();
                    double rawY = clipContainer.getLayoutY();

                    int finalCol = (int) Math.round(rawX / barWidth);
                    if (finalCol < 0) finalCol = 0;
                    double snappedX = finalCol * barWidth;

                    int finalRow = (int) Math.round(rawY / TRACK_HEIGHT);
                    if (finalRow < 0) finalRow = 0;
                    if (finalRow >= NUM_TRACKS) finalRow = NUM_TRACKS - 1;
                    double snappedY = finalRow * TRACK_HEIGHT;

                    clipContainer.setLayoutX(snappedX);
                    clipContainer.setLayoutY(snappedY);

                    item.setStartBar(finalCol + 1);
                    item.setTrackIndex(finalRow);
                }
            }
        });
        // --- 5. CLICK DERECHO (Borrar) ---
        clipContainer.addEventFilter(javafx.scene.input.MouseEvent.MOUSE_CLICKED, e -> {
            if (e.getButton() == javafx.scene.input.MouseButton.SECONDARY) {
                playlistGridContent.getChildren().remove(clipContainer);
                songData.remove(item);
                e.consume();
            }
        });
        playlistGridContent.getChildren().add(clipContainer);
    }

    private void initializeSongTimeline() {
        songTimeline = new Timeline(new KeyFrame(Duration.millis(20), e -> {
            updatePlayhead();
        }));
        songTimeline.setCycleCount(Animation.INDEFINITE);
    }

    private void updatePlayhead() {
        double bpm = GlobalState.getBpm();
        double oldX = currentPlayheadX;

        double pixelsPerTick = ((bpm / 60.0) * CELL_WIDTH) / 50.0;

        currentPlayheadX += pixelsPerTick;

        checkCollisions(oldX, currentPlayheadX);

        double totalWidth = NUM_BARS * (CELL_WIDTH * 4);
        if (currentPlayheadX >= totalWidth) {
            currentPlayheadX = 0;
            previousPlayheadX = 0;
        }

        playheadLine.setStartX(currentPlayheadX);
        playheadLine.setEndX(currentPlayheadX);
    }

    private void checkCollisions(double oldX, double newX) {
        double barWidth = CELL_WIDTH * 4;

        for (PlaylistItem item : songData) {

            double itemStartX = (item.getStartBar() - 1) * barWidth;
            double itemEndX = itemStartX + (item.getDurationBars() * barWidth);

            if (itemStartX >= oldX && itemStartX < newX) {
                triggerPattern(item);
            }

            if (itemEndX >= oldX && itemEndX < newX) {
                stopPattern(item);
            }
        }
    }

    // MÉTODO PARA PARAR EL PATRÓN
    private void stopPattern(PlaylistItem item) {
        ChannelRackController rack = GlobalState.getChannelRackController();
        if (rack != null) {
            rack.stopSoundByName(item.getPatternName());
        }
    }

    private void triggerPattern(PlaylistItem item) {
        // Pedimos al Channel Rack que suene
        ChannelRackController rack = GlobalState.getChannelRackController();
        if (rack != null) {
            // Sonido
            rack.playSoundByName(item.getPatternName());
        }
    }
    // Menú contextual de la lista de patterns
    private void setupPatternListViewContextMenu() {
        patternListView.setCellFactory(lv -> {
            ListCell<String> cell = new ListCell<>();
            ContextMenu contextMenu = new ContextMenu();
            MenuItem deleteItem = new MenuItem("Delete pattern");
            deleteItem.setStyle("-fx-text-fill: #ff5555;");
            deleteItem.setOnAction(event -> {
                String itemToRemove = cell.getItem();
                if (itemToRemove != null) {
                    patternListView.getItems().remove(itemToRemove);
                }
            });

            contextMenu.getItems().add(deleteItem);
            cell.textProperty().bind(cell.itemProperty());
            cell.emptyProperty().addListener((obs, wasEmpty, isNowEmpty) -> {
                if (isNowEmpty) {
                    cell.setContextMenu(null);
                } else {
                    cell.setContextMenu(contextMenu);
                }
            });
            return cell;
        });
    }

    @FXML
    private void handleExportWav(ActionEvent event) {
        javafx.stage.FileChooser fileChooser = new javafx.stage.FileChooser();
        fileChooser.setTitle("Export song");
        fileChooser.getExtensionFilters().add(
                new javafx.stage.FileChooser.ExtensionFilter("Archivo WAV", "*.wav")
        );

        fileChooser.setInitialFileName("my_hydra_project.wav");

        File file = fileChooser.showSaveDialog(btnPlaySong.getScene().getWindow());

        if (file != null) {
            double bpm = GlobalState.getBpm();
            ChannelRackController rack = GlobalState.getChannelRackController(); 
            new Thread(() -> {
                try {
                    AudioExporter.exportSong(file, songData, bpm, rack);
                    Platform.runLater(() -> {
                        Alert alert = new Alert(Alert.AlertType.INFORMATION);
                        alert.setContentText("Exported song to " + file.getAbsolutePath());
                        alert.setHeaderText("Your song has been succesfully exported.");
                        alert.setTitle("Successful export");
                        alert.getDialogPane().setGraphic(null);
                        alert.showAndWait();
                    });
                } catch (Exception e) {
                    e.printStackTrace();
                    Platform.runLater(() -> {
                        Alert alert = new Alert(Alert.AlertType.INFORMATION);
                        alert.setContentText("An error has occurred while exporting your song");
                        alert.setHeaderText("Exporting error.");
                        alert.setTitle("Error");
                        alert.getDialogPane().setGraphic(null);
                        alert.showAndWait();
                    });
                }
            }).start();
        }
    }

    // Librería de samples
    private void setupLibrary() {
        // 1. Ruta Raíz
        File rootDir = SAMPLES_DIRECTORY;

        // 2. Crear el nodo raíz del árbol (invisible si showRoot es false)
        TreeItem<File> rootItem = new TreeItem<>(rootDir);
        rootItem.setExpanded(true);

        // 3. Llenar el árbol recursivamente
        populateTree(rootDir, rootItem);

        // 4. Añadir las carpetas externas que el usuario haya agregado
        loadLibraryFolders();
        for (File folder : externalLibraryFolders) {
            rootItem.getChildren().add(createFolderNode(folder));
        }

        // 5. Asignar al componente visual
        libraryTreeView.setRoot(rootItem);
        libraryTreeView.setShowRoot(false); 

        // 6. PERSONALIZAR CÓMO SE VE CADA FILA (CellFactory)
        libraryTreeView.setCellFactory(tv -> new TreeCell<File>() {
            private final ContextMenu contextMenu = new ContextMenu();
            private final MenuItem refreshItem = new MenuItem("Refresh");
            private final SeparatorMenuItem separatorItem = new SeparatorMenuItem();
            private final MenuItem removeItem = new MenuItem("Remove from library");

            {
                refreshItem.setOnAction(event -> refreshLibraryFolder(getTreeItem()));

                removeItem.setStyle("-fx-text-fill: #ff5555;");
                removeItem.setOnAction(event -> removeLibraryFolder(getTreeItem()));

                contextMenu.getItems().addAll(refreshItem, separatorItem, removeItem);
            }

            @Override
            protected void updateItem(File item, boolean empty) {
                super.updateItem(item, empty);

                if (empty || item == null) {
                    setText(null);
                    setGraphic(null);
                    setContextMenu(null);
                } else {
                    setText(item.getName());
                    // Añadir iconos simples si es carpeta o archivo
                    // setGraphic(new ImageView(...));
                    if (item.isDirectory()) {
                        setStyle("-fx-text-fill: #ffffff; -fx-font-weight: bold;"); // Carpetas en blanco negrita

                        // Solo las carpetas externas se pueden quitar de la Library
                        boolean canRemove = isManagedLibraryFolder(item);
                        separatorItem.setVisible(canRemove);
                        removeItem.setVisible(canRemove);
                        setContextMenu(contextMenu);
                    } else {
                        setStyle("-fx-text-fill: #aaaaaa;"); // Archivos en gris
                        setContextMenu(null);
                    }
                }
            }
        });
        // 7. Evento de Doble Clic
        libraryTreeView.setOnMouseClicked(this::handleLibraryClick);
    }

    private TreeItem<File> createFolderNode(File folder) {
        TreeItem<File> item = new TreeItem<>(folder);
        populateTree(folder, item);
        item.setExpanded(true);
        return item;
    }

    private void populateTree(File dir, TreeItem<File> parentItem) {
        File[] files = dir.listFiles();
        if (files != null) {
            for (File file : files) {
                boolean isWav = file.isFile() && file.getName().toLowerCase().endsWith(".wav");
                boolean isDir = file.isDirectory();

                if (isWav || isDir) {
                    TreeItem<File> item = new TreeItem<>(file);
                    parentItem.getChildren().add(item);

                    if (isDir) {
                        populateTree(file, item);
                    }
                }
            }
        }
    }

    private void handleLibraryClick(MouseEvent event) {
        if (event.getClickCount() == 2) {
            TreeItem<File> selectedItem = (TreeItem<File>) libraryTreeView.getSelectionModel().getSelectedItem();

            if (selectedItem != null && selectedItem.getValue().isFile()) {
                File selectedFile = selectedItem.getValue();
                loadSampleToRack(selectedFile);
            }
        }
    }

    @FXML
    private void handleAddToLibrary(ActionEvent event) {
        DirectoryChooser directoryChooser = new DirectoryChooser();
        directoryChooser.setTitle("Add sample folder");
        directoryChooser.setInitialDirectory(SAMPLES_DIRECTORY.isDirectory()
                ? SAMPLES_DIRECTORY
                : new File(System.getProperty("user.home")));

        File selectedFolder = directoryChooser.showDialog(btnAddToLibrary.getScene().getWindow());
        if (selectedFolder == null || !selectedFolder.isDirectory()) {
            return;
        }

        File folder = selectedFolder.getAbsoluteFile();
        if (isFolderInLibrary(folder)) {
            showLibraryAlert(Alert.AlertType.INFORMATION, "Folder already in the library",
                    "That folder is already shown in the Library.");
            return;
        }

        externalLibraryFolders.add(folder);
        saveLibraryFolders();

        TreeItem<File> rootItem = (TreeItem<File>) libraryTreeView.getRoot();
        if (rootItem == null) {
            return;
        }

        TreeItem<File> folderNode = createFolderNode(folder);
        rootItem.getChildren().add(folderNode);
        libraryTreeView.getSelectionModel().select(folderNode);
    }

    private void refreshLibraryFolder(TreeItem<File> item) {
        if (item == null || item.getValue() == null) {
            return;
        }

        File folder = item.getValue();
        if (!folder.isDirectory()) {
            showLibraryAlert(Alert.AlertType.ERROR, "Folder not available",
                    "The folder \"" + folder.getPath() + "\" no longer exists.");
            return;
        }

        item.getChildren().clear();
        populateTree(folder, item);
        item.setExpanded(true);
    }

    private void removeLibraryFolder(TreeItem<File> item) {
        if (item == null || item.getValue() == null) {
            return;
        }

        File folder = item.getValue().getAbsoluteFile();
        boolean removed = externalLibraryFolders.removeIf(
                existing -> existing.getAbsoluteFile().equals(folder));
        if (!removed) {
            return;
        }

        saveLibraryFolders();

        TreeItem<File> rootItem = item.getParent();
        if (rootItem != null) {
            rootItem.getChildren().remove(item);
        }
    }

    private boolean isFolderInLibrary(File folder) {
        Path samplesPath = SAMPLES_DIRECTORY.toPath().toAbsolutePath().normalize();
        Path folderPath = folder.toPath().toAbsolutePath().normalize();
        if (folderPath.startsWith(samplesPath)) {
            return true;
        }
        return isManagedLibraryFolder(folder);
    }

    private boolean isManagedLibraryFolder(File folder) {
        for (File existing : externalLibraryFolders) {
            if (existing.getAbsoluteFile().equals(folder.getAbsoluteFile())) {
                return true;
            }
        }
        return false;
    }

    private void loadLibraryFolders() {
        externalLibraryFolders.clear();

        String saved;
        try {
            saved = LIBRARY_PREFERENCES.get(PREF_LIBRARY_FOLDERS, "");
        } catch (RuntimeException e) {
            System.err.println("Could not read the library folders: " + e.getMessage());
            return;
        }

        for (String path : saved.split("\n")) {
            if (path.isBlank()) {
                continue;
            }

            File folder = new File(path);
            // Ignore folders that were moved or deleted since the last run
            if (folder.isDirectory()) {
                externalLibraryFolders.add(folder.getAbsoluteFile());
            }
        }
    }

    private void saveLibraryFolders() {
        StringBuilder builder = new StringBuilder();
        for (File folder : externalLibraryFolders) {
            if (builder.length() > 0) {
                builder.append('\n');
            }
            builder.append(folder.getAbsolutePath());
        }

        try {
            LIBRARY_PREFERENCES.put(PREF_LIBRARY_FOLDERS, builder.toString());
        } catch (RuntimeException e) {
            System.err.println("Could not save the library folders: " + e.getMessage());
        }
    }

    private void showLibraryAlert(Alert.AlertType type, String header, String content) {
        Alert alert = new Alert(type);
        alert.setTitle("Library");
        alert.setHeaderText(header);
        alert.setContentText(content);
        alert.getDialogPane().setGraphic(null);
        alert.showAndWait();
    }

    private void loadSampleToRack(File sampleFile) {
        ChannelRackController rack = GlobalState.getChannelRackController();
        if (rack != null) {
            String fileName = sampleFile.getName();
            String trackName = fileName.replace(".wav", "");
            rack.addTrackFromLibrary(trackName, sampleFile);
        } else {
            openChannelRackButton.fire();
            loadSampleToRack(sampleFile);
        }
    }

    private void setWindowIcon(Stage stage) {
        try {
            Image icon = new Image(getClass().getResourceAsStream("/images/logo.png"));
            stage.getIcons().add(icon);
        } catch (Exception e) {
            System.err.println("No se pudo cargar el icono de la ventana.");
        }
    }

    public void salir(ActionEvent actionEvent) {
        System.exit(0);
    }
}