package com.sparrowwallet.sparrow.joinstr;
import com.sparrowwallet.sparrow.AppServices;
import com.sparrowwallet.sparrow.Theme;
import com.sparrowwallet.sparrow.io.Config;

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
import javafx.scene.Node;
import javafx.scene.control.Toggle;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

public class JoinstrController extends JoinstrFormController implements IThreadExecutor {

    private static final Logger logger = Logger.getLogger(JoinstrController.class.getName());

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
        String darkCss = getClass().getResource("../darktheme.css").toExternalForm();
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
