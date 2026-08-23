package com.sparrowwallet.sparrow.joinstr;
import com.sparrowwallet.sparrow.AppServices;
import com.sparrowwallet.sparrow.Theme;
import com.sparrowwallet.sparrow.io.Config;
import com.google.common.net.HostAndPort;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URL;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.Toggle;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

public class JoinstrController extends JoinstrFormController implements IThreadExecutor {

    private static final Logger logger = Logger.getLogger(JoinstrController.class.getName());
    private static final org.slf4j.Logger log = LoggerFactory.getLogger(JoinstrController.class);

    private Stage stage;

    protected String selectedWallet;

    @FXML
    private StackPane joinstrPane;

    @FXML
    private VBox joinstrMenuBox;

    @FXML
    private ToggleGroup joinstrMenu;

    private final AtomicBoolean isUpdatingDisplay = new AtomicBoolean();
    private JoinstrPool selectedPool;
    private final Map<JoinstrDisplay, Node> displayNodeCache = new EnumMap<>(JoinstrDisplay.class);
    private final Map<JoinstrDisplay, JoinstrFormController> controllerCache = new EnumMap<>(JoinstrDisplay.class);
    private ExecutorService executorService;
    @Override
    public ExecutorService getExecutorService() {
        if(executorService == null) {
            executorService = createExecutorService();
            setExecutorService(executorService);
        }
        return executorService;
    }

    @Override
    public void setExecutorService(ExecutorService executorService) {
        this.executorService = executorService;
    }

    public JoinstrController() { }

    public void initializeView() {
        // Ensure Tor is running for Joinstr circuit isolation
        AppServices.get().startTor();
        // Route nostr-java WebSocket connections through Tor SOCKS proxy
        awaitTor();

        joinstrMenu.selectedToggleProperty().addListener((observable, oldValue, selectedToggle) -> {
            if(selectedToggle == null) {
                if(oldValue != null)
                    oldValue.setSelected(true);
                return;
            }
            JoinstrDisplay display = (JoinstrDisplay)selectedToggle.getUserData();
            setJoinstrDisplay(display);
        });

        for(Toggle toggle : joinstrMenu.getToggles()) {
            ToggleButton toggleButton = (ToggleButton) toggle;
            toggleButton.managedProperty().bind(toggleButton.visibleProperty());
        }

        joinstrMenuBox.managedProperty().bind(joinstrMenuBox.visibleProperty());
        joinstrMenuBox.visibleProperty().bind(getJoinstrForm().lockedProperty().not());

        // Set theme CSS
        String darkCss = AppServices.class.getResource("darktheme.css").toExternalForm();
        if(Config.get().getTheme() == Theme.DARK) {
            if(!stage.getScene().getStylesheets().contains(darkCss)) {
                stage.getScene().getStylesheets().add(darkCss);
            }
        } else {
            stage.getScene().getStylesheets().remove(darkCss);
        }

        setJoinstrDisplay(JoinstrDisplay.NEW_POOL);
    }

    @Override
    public void refreshView() {

    }

    public JoinstrPool getSelectedPool() {
        return this.selectedPool;
    }

    public void setSelectedPool(JoinstrPool selectedPool) {
        this.selectedPool = selectedPool;
    }

    public void setJoinstrDisplay(JoinstrDisplay display) {
        if(!isUpdatingDisplay.compareAndSet(false, true))
            return;

        int displayIndex = -1;
        for(int idx=0;idx<joinstrPane.getChildren().size();idx++) {
            Node joinstrDisplay = joinstrPane.getChildren().get(idx);
            if(joinstrDisplay.getUserData().equals(display)) {
                displayIndex = idx;
                joinstrDisplay.setViewOrder(0);
            } else {
                joinstrDisplay.setViewOrder(1);
            }
        }

        for(int idx=0;idx<joinstrMenu.getToggles().size();idx++) {
            if(joinstrMenu.getToggles().get(idx).getUserData().equals(display)) {
                joinstrMenu.selectToggle(joinstrMenu.getToggles().get(idx));
                break;
            }
        }

        try {

            Node joinstrDisplay = displayNodeCache.get(display);
            JoinstrFormController currentFormController = controllerCache.get(display);

            if(joinstrDisplay == null || currentFormController == null) {

                URL url = AppServices.class.getResource("joinstr/" + display.toString().toLowerCase(Locale.ROOT) + ".fxml");
                if(url == null) {
                    throw new IllegalStateException("Cannot find joinstr/" + display.toString().toLowerCase(Locale.ROOT) + ".fxml");
                }

                FXMLLoader displayLoader = new FXMLLoader(url);
                joinstrDisplay = displayLoader.load();

                joinstrDisplay.setUserData(display);

                // Remove existing display to refresh data
                if(displayIndex != -1) {
                    joinstrPane.getChildren().remove(displayIndex);
                }
                joinstrPane.getChildren().add(joinstrDisplay);

                currentFormController = displayLoader.getController();
                JoinstrForm joinstrForm = getJoinstrForm();
                currentFormController.setJoinstrController(this);
                currentFormController.setJoinstrForm(joinstrForm);
                currentFormController.initializeView();

                displayNodeCache.put(display, joinstrDisplay);
                controllerCache.put(display, currentFormController);

            }

            currentFormController.refreshView();
            joinstrDisplay.setViewOrder(0);

        } catch (IOException e) {
            throw new IllegalStateException("Can't find pane", e);
        } finally {
            isUpdatingDisplay.set(false);
        }

    }

    public Stage getStage() {
        return this.stage;
    }

    public void setStage(Stage stage) {
        this.stage = stage;
    }

    /**
     * Wait for Tor before any joinstr traffic is possible, and say so if it never arrives.
     *
     * The proxy itself is applied per nostr connection by JoinstrTransport, so no JVM wide system
     * property is set and the rest of Sparrow's traffic is unaffected.
     */
    private void awaitTor() {
        if(AppServices.isTorRunning()) {
            log.info("[Joinstr] Tor is already running, joinstr requests will use it");
            return;
        }

        Stage progress = showTorProgress();

        Thread t = new Thread(() -> {
            try {
                int waited = 0;
                while (!AppServices.isTorRunning() && waited < 90) {
                    Thread.sleep(2000);
                    waited += 2;
                }
                if (AppServices.isTorRunning()) {
                    log.info("[Joinstr] Tor is running, joinstr requests will use it");
                    javafx.application.Platform.runLater(progress::close);
                } else {
                    log.warn("[Joinstr] Tor not ready after 90s, joinstr requests will be refused");
                    javafx.application.Platform.runLater(() -> {
                        progress.close();
                        AppServices.showErrorDialog("Tor Not Running", JoinstrTransport.NOT_READY);
                    });
                }
            } catch (InterruptedException e) {
                javafx.application.Platform.runLater(progress::close);
                Thread.currentThread().interrupt();
            }
        });
        t.setDaemon(true);
        t.setName("TorReadinessWatcher");
        t.start();
    }

    /**
     * Tor can take a while on first start, and joinstr cannot send anything until it is up.
     * Show that rather than leaving the window looking stuck.
     */
    private Stage showTorProgress() {
        Label message = new Label("Starting Tor, this can take a minute on first run.");
        ProgressBar progressBar = new ProgressBar();
        progressBar.setProgress(ProgressBar.INDETERMINATE_PROGRESS);
        progressBar.setMaxWidth(Double.MAX_VALUE);

        VBox content = new VBox(12, message, progressBar);
        content.setPadding(new Insets(20));
        content.setPrefWidth(360);

        Scene scene = new Scene(content);
        if(Config.get().getTheme() == Theme.DARK) {
            scene.getStylesheets().add(AppServices.class.getResource("darktheme.css").toExternalForm());
        }

        Stage progress = new Stage(StageStyle.UTILITY);
        progress.setTitle("Coinjoin");
        progress.initOwner(stage);
        progress.initModality(Modality.NONE);
        progress.setResizable(false);
        progress.setScene(scene);
        progress.show();

        return progress;
    }

    @Override
    public void close() {
        try {

            for(JoinstrFormController formController : controllerCache.values()) {
                formController.close();
            }
            controllerCache.clear();

            shutdownThreads();
            stage.close();

        } catch (Exception e) {
            logger.severe("Error stopping threads: " + e.getMessage());
        }
    }

}
