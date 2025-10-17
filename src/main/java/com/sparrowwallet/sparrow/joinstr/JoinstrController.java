package com.sparrowwallet.sparrow.joinstr;
import com.sparrowwallet.sparrow.AppServices;
import com.sparrowwallet.sparrow.Theme;
import com.sparrowwallet.sparrow.io.Config;

import java.io.IOException;
import java.net.URL;
import java.util.Locale;

import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import javafx.scene.control.Toggle;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

public class JoinstrController extends JoinstrFormController {

    private Stage stage;

    protected String selectedWallet;

    @FXML
    private StackPane joinstrPane;

    @FXML
    private VBox joinstrMenuBox;

    @FXML
    private ToggleGroup joinstrMenu;

    private JoinstrPool selectedPool;

    public JoinstrController() {

    }

    public void initializeView() {
        joinstrMenu.selectedToggleProperty().addListener((observable, oldValue, selectedToggle) -> {
            if(selectedToggle == null) {
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

    public JoinstrPool getSelectedPool() {
        return this.selectedPool;
    }

    public void setSelectedPool(JoinstrPool selectedPool) {
        this.selectedPool = selectedPool;
    }

    public void setJoinstrDisplay(JoinstrDisplay display) {

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

            URL url = AppServices.class.getResource("joinstr/" + display.toString().toLowerCase(Locale.ROOT) + ".fxml");
            if(url == null) {
                throw new IllegalStateException("Cannot find joinstr/" + display.toString().toLowerCase(Locale.ROOT) + ".fxml");
            }

            FXMLLoader displayLoader = new FXMLLoader(url);
            Node joinstrDisplay = displayLoader.load();

            joinstrDisplay.setUserData(display);
            joinstrDisplay.setViewOrder(1);

            // Remove existing display to refresh data
            if(displayIndex != -1) {
                joinstrPane.getChildren().remove(displayIndex);
            }
            joinstrPane.getChildren().add(joinstrDisplay);

            JoinstrFormController controller = displayLoader.getController();
            JoinstrForm joinstrForm = getJoinstrForm();
            controller.setJoinstrController(this);
            controller.setJoinstrForm(joinstrForm);
            controller.initializeView();

        } catch (IOException e) {
            throw new IllegalStateException("Can't find pane", e);
        }

    }

    public void setStage(Stage stage) {
        this.stage = stage;
    }

    public void close(ActionEvent event) {
        stage.close();
    }

}
