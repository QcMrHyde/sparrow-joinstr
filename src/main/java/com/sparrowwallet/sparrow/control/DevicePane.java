package com.sparrowwallet.sparrow.control;

import com.google.common.base.Throwables;
import com.sparrowwallet.drongo.ExtendedKey;
import com.sparrowwallet.drongo.KeyDerivation;
import com.sparrowwallet.drongo.OutputDescriptor;
import com.sparrowwallet.drongo.address.Address;
import com.sparrowwallet.drongo.crypto.ChildNumber;
import com.sparrowwallet.drongo.crypto.ECKey;
import com.sparrowwallet.drongo.policy.Policy;
import com.sparrowwallet.drongo.policy.PolicyType;
import com.sparrowwallet.drongo.protocol.ScriptType;
import com.sparrowwallet.drongo.protocol.Sha256Hash;
import com.sparrowwallet.drongo.psbt.PSBT;
import com.sparrowwallet.drongo.wallet.*;
import com.sparrowwallet.sparrow.AppServices;
import com.sparrowwallet.sparrow.EventManager;
import com.sparrowwallet.sparrow.event.*;
import com.sparrowwallet.sparrow.io.*;
import com.sparrowwallet.sparrow.glyphfont.FontAwesome5;
import com.sparrowwallet.sparrow.net.ElectrumServer;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.concurrent.Service;
import javafx.concurrent.WorkerStateEvent;
import javafx.event.EventHandler;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import org.controlsfx.control.textfield.CustomPasswordField;
import org.controlsfx.glyphfont.Glyph;
import org.controlsfx.validation.ValidationResult;
import org.controlsfx.validation.ValidationSupport;
import org.controlsfx.validation.Validator;
import org.controlsfx.validation.decoration.StyleClassValidationDecoration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public class DevicePane extends TitledDescriptionPane {
    private static final Logger log = LoggerFactory.getLogger(DevicePane.class);

    private final DeviceOperation deviceOperation;
    private final Wallet wallet;
    private final PSBT psbt;
    private final OutputDescriptor outputDescriptor;
    private final KeyDerivation keyDerivation;
    private final String message;
    private final List<StandardAccount> availableAccounts;
    private final Device device;

    private CustomPasswordField pinField;
    private Button unlockButton;
    private Button enterPinButton;
    private Button setPassphraseButton;
    private ButtonBase importButton;
    private Button signButton;
    private Button displayAddressButton;
    private Button signMessageButton;
    private Button discoverKeystoresButton;
    private ButtonBase getPrivateKeyButton;
    private Button getAddressButton;

    private final SimpleStringProperty passphrase = new SimpleStringProperty("");
    private final SimpleStringProperty pin = new SimpleStringProperty("");
    private final StringProperty messageProperty = new SimpleStringProperty("");

    private boolean defaultDevice;

    public DevicePane(Wallet wallet, Device device, boolean defaultDevice, KeyDerivation requiredDerivation) {
        super(device.getModel().toDisplayString(), "", "", device.getModel());
        this.deviceOperation = DeviceOperation.IMPORT;
        this.wallet = wallet;
        this.psbt = null;
        this.outputDescriptor = null;
        this.keyDerivation = requiredDerivation;
        this.message = null;
        this.availableAccounts = null;
        this.device = device;
        this.defaultDevice = defaultDevice;

        setDefaultStatus();
        showHideLink.setVisible(false);

        createSetPassphraseButton();
        createImportButton();

        initialise(device);

        messageProperty.addListener((observable, oldValue, newValue) -> {
            Platform.runLater(() -> setDescription(newValue));
        });

        buttonBox.getChildren().addAll(setPassphraseButton, importButton);
    }

    public DevicePane(Wallet wallet, PSBT psbt, Device device, boolean defaultDevice) {
        super(device.getModel().toDisplayString(), "", "", device.getModel());
        this.deviceOperation = DeviceOperation.SIGN;
        this.wallet = wallet;
        this.psbt = psbt;
        this.outputDescriptor = null;
        this.keyDerivation = null;
        this.message = null;
        this.availableAccounts = null;
        this.device = device;
        this.defaultDevice = defaultDevice;

        setDefaultStatus();
        showHideLink.setVisible(false);

        createSetPassphraseButton();
        createSignButton();

        initialise(device);

        messageProperty.addListener((observable, oldValue, newValue) -> {
            Platform.runLater(() -> setDescription(newValue));
        });

        buttonBox.getChildren().addAll(setPassphraseButton, signButton);
    }

    public DevicePane(Wallet wallet, OutputDescriptor outputDescriptor, Device device, boolean defaultDevice) {
        super(device.getModel().toDisplayString(), "", "", device.getModel());
        this.deviceOperation = DeviceOperation.DISPLAY_ADDRESS;
        this.wallet = wallet;
        this.psbt = null;
        this.outputDescriptor = outputDescriptor;
        this.keyDerivation = null;
        this.message = null;
        this.availableAccounts = null;
        this.device = device;
        this.defaultDevice = defaultDevice;

        setDefaultStatus();
        showHideLink.setVisible(false);

        createSetPassphraseButton();
        createDisplayAddressButton();

        initialise(device);

        buttonBox.getChildren().addAll(setPassphraseButton, displayAddressButton);
    }

    public DevicePane(Wallet wallet, String message, KeyDerivation keyDerivation, Device device, boolean defaultDevice) {
        super(device.getModel().toDisplayString(), "", "", device.getModel());
        this.deviceOperation = DeviceOperation.SIGN_MESSAGE;
        this.wallet = wallet;
        this.psbt = null;
        this.outputDescriptor = null;
        this.keyDerivation = keyDerivation;
        this.message = message;
        this.availableAccounts = null;
        this.device = device;
        this.defaultDevice = defaultDevice;

        setDefaultStatus();
        showHideLink.setVisible(false);

        createSetPassphraseButton();
        createSignMessageButton();

        initialise(device);

        messageProperty.addListener((observable, oldValue, newValue) -> {
            Platform.runLater(() -> setDescription(newValue));
        });

        buttonBox.getChildren().addAll(setPassphraseButton, signMessageButton);
    }

    public DevicePane(Wallet wallet, List<StandardAccount> availableAccounts, Device device, boolean defaultDevice) {
        super(device.getModel().toDisplayString(), "", "", device.getModel());
        this.deviceOperation = DeviceOperation.DISCOVER_KEYSTORES;
        this.wallet = wallet;
        this.psbt = null;
        this.outputDescriptor = null;
        this.keyDerivation = null;
        this.message = null;
        this.device = device;
        this.defaultDevice = defaultDevice;
        this.availableAccounts = availableAccounts;

        setDefaultStatus();
        showHideLink.setVisible(false);

        createSetPassphraseButton();
        createDiscoverKeystoresButton();

        initialise(device);

        buttonBox.getChildren().addAll(setPassphraseButton, discoverKeystoresButton);
    }

    public DevicePane(DeviceOperation deviceOperation, Device device, boolean defaultDevice) {
        super(device.getModel().toDisplayString(), "", "", device.getModel());
        this.deviceOperation = deviceOperation;
        this.wallet = null;
        this.psbt = null;
        this.outputDescriptor = null;
        this.keyDerivation = null;
        this.message = null;
        this.device = device;
        this.defaultDevice = defaultDevice;
        this.availableAccounts = null;

        setDefaultStatus();
        showHideLink.setVisible(false);

        ButtonBase button;
        if(deviceOperation == DeviceOperation.GET_PRIVATE_KEY) {
            createGetPrivateKeyButton();
            button = getPrivateKeyButton;
        } else if(deviceOperation == DeviceOperation.GET_ADDRESS) {
            createGetAddressButton();
            button = getAddressButton;
        } else {
            throw new UnsupportedOperationException("Cannot construct device pane for operation " + deviceOperation);
        }

        initialise(device);

        messageProperty.addListener((observable, oldValue, newValue) -> {
            Platform.runLater(() -> setDescription(newValue));
        });

        buttonBox.getChildren().add(button);
    }

    private void initialise(Device device) {
        if(device.isNeedsPinSent()) {
            unlockButton.setDefaultButton(defaultDevice);
            unlockButton.setVisible(true);
        } else if(device.isNeedsPassphraseSent()) {
            setPassphraseButton.setVisible(true);
        } else if(device.getError() != null) {
            setError("Error", device.getError());
            Platform.runLater(() -> {
                setExpanded(true);
            });
        } else {
            showOperationButton();
        }
    }

    @Override
    protected Control createButton() {
        createUnlockButton();
        return unlockButton;
    }

    private void setDefaultStatus() {
        setDescription(device.isNeedsPinSent() ? "Locked" : device.isNeedsPassphraseSent() ? "Passphrase Required" : device.isCard() ? "Leave card on reader" : "Unlocked");
    }

    private void createUnlockButton() {
        unlockButton = new Button("Unlock");
        unlockButton.setAlignment(Pos.CENTER_RIGHT);
        unlockButton.setOnAction(event -> {
            unlockButton.setDisable(true);
            unlock(device);
        });
        unlockButton.managedProperty().bind(unlockButton.visibleProperty());
        unlockButton.setVisible(false);
    }

    private void createSetPassphraseButton() {
        setPassphraseButton = new Button("Set Passphrase");
        setPassphraseButton.setAlignment(Pos.CENTER_RIGHT);
        setPassphraseButton.setOnAction(event -> {
            setPassphraseButton.setDisable(true);
            setContent(getPassphraseEntry());
            setExpanded(true);
        });
        setPassphraseButton.managedProperty().bind(setPassphraseButton.visibleProperty());
        setPassphraseButton.setVisible(false);
    }

    private void createImportButton() {
        importButton = keyDerivation == null ? new SplitMenuButton() : new Button();
        importButton.setAlignment(Pos.CENTER_RIGHT);
        importButton.setText("Import Keystore");
        importButton.setOnAction(event -> {
            importButton.setDisable(true);
            List<ChildNumber> defaultDerivation = wallet.getScriptType() == null ? ScriptType.P2WPKH.getDefaultDerivation() : wallet.getScriptType().getDefaultDerivation();
            importKeystore(keyDerivation == null ? defaultDerivation : keyDerivation.getDerivation());
        });

        if(importButton instanceof SplitMenuButton importMenuButton) {
            if(wallet.getScriptType() == null) {
                ScriptType[] scriptTypes = new ScriptType[] {ScriptType.P2WPKH, ScriptType.P2SH_P2WPKH, ScriptType.P2PKH, ScriptType.P2TR};
                for(ScriptType scriptType : scriptTypes) {
                    MenuItem item = new MenuItem(scriptType.getDescription());
                    final List<ChildNumber> derivation = scriptType.getDefaultDerivation();
                    item.setOnAction(event -> {
                        importMenuButton.setDisable(true);
                        importKeystore(derivation);
                    });
                    importMenuButton.getItems().add(item);
                }
            } else {
                String[] accounts = new String[] {"Default Account #0", "Account #1", "Account #2", "Account #3", "Account #4", "Account #5", "Account #6", "Account #7", "Account #8", "Account #9"};
                int scriptAccountsLength = ScriptType.P2SH.equals(wallet.getScriptType()) ? 1 : accounts.length;
                for(int i = 0; i < scriptAccountsLength; i++) {
                    MenuItem item = new MenuItem(accounts[i]);
                    final List<ChildNumber> derivation = wallet.getScriptType().getDefaultDerivation(i);
                    item.setOnAction(event -> {
                        importMenuButton.setDisable(true);
                        importKeystore(derivation);
                    });
                    importMenuButton.getItems().add(item);
                }
            }
        }
        importButton.managedProperty().bind(importButton.visibleProperty());
        importButton.setVisible(false);
    }

    private void createSignButton() {
        signButton = new Button("Sign");
        signButton.setAlignment(Pos.CENTER_RIGHT);
        signButton.setMinWidth(44);
        signButton.setOnAction(event -> {
            signButton.setDisable(true);
            sign();
        });
        signButton.managedProperty().bind(signButton.visibleProperty());
        signButton.setVisible(false);
    }

    private void createDisplayAddressButton() {
        displayAddressButton = new Button("Display Address");
        displayAddressButton.setAlignment(Pos.CENTER_RIGHT);
        displayAddressButton.setOnAction(event -> {
            displayAddressButton.setDisable(true);
            displayAddress();
        });
        displayAddressButton.managedProperty().bind(displayAddressButton.visibleProperty());
        displayAddressButton.setVisible(false);

        List<String> fingerprints = outputDescriptor.getExtendedPublicKeys().stream().map(extKey -> outputDescriptor.getKeyDerivation(extKey).getMasterFingerprint()).collect(Collectors.toList());
        if(device.getFingerprint() != null && !fingerprints.contains(device.getFingerprint())) {
            displayAddressButton.setDisable(true);
        }
    }

    private void createSignMessageButton() {
        signMessageButton = new Button("Sign Message");
        signMessageButton.setAlignment(Pos.CENTER_RIGHT);
        signMessageButton.setOnAction(event -> {
            signMessageButton.setDisable(true);
            signMessage();
        });
        signMessageButton.managedProperty().bind(signMessageButton.visibleProperty());
        signMessageButton.setVisible(false);

        if(device.getFingerprint() != null && !device.getFingerprint().equals(keyDerivation.getMasterFingerprint())) {
            signMessageButton.setDisable(true);
        }
    }

    private void createDiscoverKeystoresButton() {
        discoverKeystoresButton = new Button("Discover");
        discoverKeystoresButton.setAlignment(Pos.CENTER_RIGHT);
        discoverKeystoresButton.setOnAction(event -> {
            discoverKeystoresButton.setDisable(true);
            discoverKeystores();
        });
        discoverKeystoresButton.managedProperty().bind(discoverKeystoresButton.visibleProperty());
        discoverKeystoresButton.setVisible(false);
    }

    private void createGetPrivateKeyButton() {
        int currentSlot = 0;
        boolean initialized = true;
        try {
            CardApi cardApi = CardApi.getCardApi(device.getModel(), null);
            currentSlot = cardApi.getCurrentSlot();
            initialized = cardApi.isInitialized();
        } catch(Exception e) {
            //ignore
        }

        getPrivateKeyButton = currentSlot > 0 ? new SplitMenuButton() : new Button();
        getPrivateKeyButton.setAlignment(Pos.CENTER_RIGHT);
        getPrivateKeyButton.setText("Get Private Key");
        getPrivateKeyButton.setOnAction(event -> {
            getPrivateKeyButton.setDisable(true);
            getPrivateKey(null);
        });

        if(getPrivateKeyButton instanceof SplitMenuButton getPrivateKeyMenuButton) {
            int[] previousSlots = IntStream.range(0, currentSlot).toArray();
            for(int previousSlot : previousSlots) {
                MenuItem previousSlotItem = new MenuItem("Slot #" + previousSlot);
                previousSlotItem.setOnAction(event -> {
                    getPrivateKeyButton.setDisable(true);
                    getPrivateKey(previousSlot);
                });
                getPrivateKeyMenuButton.getItems().add(previousSlotItem);
            }
            if(initialized) {
                int finalSlot = currentSlot;
                MenuItem currentSlotItem = new MenuItem("Current Slot");
                currentSlotItem.setOnAction(event -> {
                    getPrivateKeyButton.setDisable(true);
                    getPrivateKey(finalSlot);
                });
                getPrivateKeyMenuButton.getItems().add(currentSlotItem);
            }
        }
        getPrivateKeyButton.managedProperty().bind(getPrivateKeyButton.visibleProperty());
        getPrivateKeyButton.setVisible(false);
    }

    private void createGetAddressButton() {
        getAddressButton = new Button("Get Address");
        getAddressButton.setAlignment(Pos.CENTER_RIGHT);
        getAddressButton.setOnAction(event -> {
            getAddressButton.setDisable(true);
            getAddress();
        });
        getAddressButton.managedProperty().bind(getAddressButton.visibleProperty());
        getAddressButton.setVisible(false);
    }

    private void unlock(Device device) {
        if(device.getModel().requiresPinPrompt()) {
            promptPin();
        }
    }

    private Node getPinEntry() {
        VBox vBox = new VBox();
        vBox.setMaxHeight(120);
        vBox.setSpacing(42);
        pinField = new ViewPasswordField();
        Platform.runLater(() -> pinField.requestFocus());
        enterPinButton = new Button("Enter PIN");
        enterPinButton.setDefaultButton(true);
        enterPinButton.setOnAction(event -> {
            enterPinButton.setDisable(true);
            sendPin(pinField.getText());
        });
        vBox.getChildren().addAll(pinField, enterPinButton);

        TilePane tilePane = new TilePane();
        tilePane.setPrefColumns(3);
        tilePane.setHgap(10);
        tilePane.setVgap(10);
        tilePane.setMaxWidth(150);
        tilePane.setMaxHeight(120);

        int[] digits = new int[] {7, 8, 9, 4, 5, 6, 1, 2, 3};
        for(int i = 0; i < digits.length; i++) {
            Button pinButton = new Button();
            Glyph circle = new Glyph(FontAwesome5.FONT_NAME, "CIRCLE");
            pinButton.setGraphic(circle);
            pinButton.setUserData(digits[i]);
            tilePane.getChildren().add(pinButton);
            pinButton.setOnAction(event -> {
                pinField.setText(pinField.getText() + pinButton.getUserData());
            });
        }

        HBox contentBox = new HBox();
        contentBox.setSpacing(50);
        contentBox.getChildren().add(tilePane);
        contentBox.getChildren().add(vBox);
        contentBox.setPadding(new Insets(10, 0, 10, 0));
        contentBox.setAlignment(Pos.TOP_CENTER);

        return contentBox;
    }

    private Node getPassphraseEntry() {
        CustomPasswordField passphraseField = new ViewPasswordField();
        passphrase.bind(passphraseField.textProperty());
        HBox.setHgrow(passphraseField, Priority.ALWAYS);
        passphraseField.setOnAction(event -> {
            setExpanded(false);
            setDescription("Confirm passphrase on device...");
            sendPassphrase(passphrase.get());
        });

        SplitMenuButton sendPassphraseButton = new SplitMenuButton();
        sendPassphraseButton.setText("Send Passphrase");
        sendPassphraseButton.getStyleClass().add("default-button");
        sendPassphraseButton.setOnAction(event -> {
            setExpanded(false);
            setDescription("Confirm passphrase on device...");
            sendPassphrase(passphrase.get());
        });

        MenuItem removePassphrase = new MenuItem("Toggle Passphrase Off");
        removePassphrase.setOnAction(event -> {
            setExpanded(false);
            setDescription("Toggling passphrase off, check device...");
            togglePassphraseOff();
        });
        sendPassphraseButton.getItems().add(removePassphrase);

        HBox contentBox = new HBox();
        contentBox.setAlignment(Pos.TOP_RIGHT);
        contentBox.setSpacing(20);
        contentBox.getChildren().add(passphraseField);
        contentBox.getChildren().add(sendPassphraseButton);
        contentBox.setPadding(new Insets(10, 30, 10, 30));

        Platform.runLater(passphraseField::requestFocus);

        return contentBox;
    }

    private Node getTogglePassphraseOn() {
        CopyableLabel label = new CopyableLabel("Passphrase is currently disabled");
        HBox.setHgrow(label, Priority.ALWAYS);

        Button togglePassphraseOn = new Button("Toggle Passphrase On");
        togglePassphraseOn.setOnAction(event -> {
            setExpanded(false);
            hideButtons(importButton, signButton, displayAddressButton, signMessageButton);
            setDescription("Toggling passphrase on, check device...");
            togglePassphraseOn();
        });

        HBox contentBox = new HBox();
        contentBox.setSpacing(20);
        contentBox.setAlignment(Pos.CENTER_LEFT);
        contentBox.getChildren().addAll(label, togglePassphraseOn);
        contentBox.setPadding(new Insets(10, 30, 10, 30));

        return contentBox;
    }

    private void hideButtons(Node... buttons) {
        for(Node button : buttons) {
            if(button != null) {
                button.setVisible(false);
            }
        }
    }

    private void promptPin() {
        Hwi.PromptPinService promptPinService = new Hwi.PromptPinService(device);
        promptPinService.setOnSucceeded(workerStateEvent -> {
            Boolean result = promptPinService.getValue();
            if(result) {
                setContent(getPinEntry());
                setExpanded(true);
            } else {
                setError("Could not request PIN", null);
                unlockButton.setDisable(false);
            }
        });
        promptPinService.setOnFailed(workerStateEvent -> {
            setError("Error", promptPinService.getException().getMessage());
            unlockButton.setDisable(false);
        });
        promptPinService.start();
    }

    private void sendPin(String pin) {
        Hwi.SendPinService sendPinService = new Hwi.SendPinService(device, pin);
        sendPinService.setOnSucceeded(workerStateEvent -> {
            Boolean result = sendPinService.getValue();
            if(result) {
                device.setNeedsPinSent(false);
                setDefaultStatus();
                setExpanded(false);
                unlockButton.setVisible(false);

                if(device.isNeedsPassphraseSent()) {
                    setPassphraseButton.setVisible(true);
                    setPassphraseButton.setDisable(true);
                    setContent(getPassphraseEntry());
                    setExpanded(true);
                } else {
                    showOperationButton();
                    if(!deviceOperation.equals(DeviceOperation.IMPORT)) {
                        setContent(getTogglePassphraseOn());
                    }
                }
            } else {
                setError("Incorrect PIN", null);
                unlockButton.setDisable(false);
                if(pinField != null) {
                    pinField.setText("");
                }
            }
        });
        sendPinService.setOnFailed(workerStateEvent -> {
            setError("Error", sendPinService.getException().getMessage());
            enterPinButton.setDisable(false);
        });
        setDescription("Unlocking...");
        showHideLink.setVisible(false);
        sendPinService.start();
    }

    private void sendPassphrase(String passphrase) {
        Hwi.EnumerateService enumerateService = new Hwi.EnumerateService(passphrase);
        enumerateService.setOnSucceeded(workerStateEvent -> {
            List<Device> devices = enumerateService.getValue();
            for (Device freshDevice : devices) {
                if (device.getPath().equals(freshDevice.getPath()) && device.getModel().equals(freshDevice.getModel())) {
                    device.setFingerprint(freshDevice.getFingerprint());
                }
            }

            if(device.getFingerprint() != null) {
                setPassphraseButton.setVisible(false);
                setDescription("Passphrase sent");
                showOperationButton();
            } else {
                setError("Passphrase send failed", null);
                setPassphraseButton.setDisable(false);
                setPassphraseButton.setVisible(true);
            }
        });
        enumerateService.setOnFailed(workerStateEvent -> {
            setError("Error", enumerateService.getException().getMessage());
            setPassphraseButton.setDisable(false);
            setPassphraseButton.setVisible(true);
        });
        enumerateService.start();
    }

    private void togglePassphraseOff() {
        Hwi.TogglePassphraseService togglePassphraseService = new Hwi.TogglePassphraseService(device);
        togglePassphraseService.setOnSucceeded(workerStateEvent -> {
            device.setNeedsPassphraseSent(false);
            setPassphraseButton.setVisible(false);
            setDescription("Unlocked");
            showOperationButton();
        });
        togglePassphraseService.setOnFailed(workerStateEvent -> {
            setError("Error", togglePassphraseService.getException().getMessage());
        });
        togglePassphraseService.start();
    }

    private void togglePassphraseOn() {
        Hwi.TogglePassphraseService togglePassphraseService = new Hwi.TogglePassphraseService(device);
        togglePassphraseService.setOnSucceeded(workerStateEvent -> {
            device.setNeedsPassphraseSent(true);
            setPassphraseButton.setVisible(true);
            setPassphraseButton.setDisable(true);
            setDescription("Enter passphrase");
            setContent(getPassphraseEntry());
            setExpanded(true);
        });
        togglePassphraseService.setOnFailed(workerStateEvent -> {
            setError("Error", togglePassphraseService.getException().getMessage());
        });
        togglePassphraseService.start();
    }

    private void importKeystore(List<ChildNumber> derivation) {
        if(device.isCard()) {
            try {
                CardApi cardApi = CardApi.getCardApi(device.getModel(), pin.get());
                if(!cardApi.isInitialized()) {
                    if(pin.get().length() < device.getModel().getMinPinLength()) {
                        setDescription(pin.get().isEmpty() ? (device.getModel().hasDefaultPin() ? "Enter PIN code" : "Choose a PIN code") : "PIN code too short");
                        setContent(getCardPinEntry(importButton));
                        showHideLink.setVisible(false);
                        setExpanded(true);
                        importButton.setDisable(false);
                        return;
                    }

                    setDescription("Card not initialized");
                    setContent(getCardInitializationPanel(cardApi, importButton, DeviceOperation.IMPORT));
                    showHideLink.setVisible(false);
                    setExpanded(true);
                    return;
                }

                Service<Keystore> importService = cardApi.getImportService(derivation, messageProperty);
                handleCardOperation(importService, importButton, "Import", true, event -> {
                    importKeystore(derivation, importService.getValue());
                });
            } catch(Exception e) {
                log.error("Import Error: " + e.getMessage(), e);
                setError("Import Error", e.getMessage());
                importButton.setDisable(false);
            }
        } else if(device.getFingerprint() == null) {
            Hwi.EnumerateService enumerateService = new Hwi.EnumerateService(passphrase.get());
            enumerateService.setOnSucceeded(workerStateEvent -> {
                List<Device> devices = enumerateService.getValue();
                for (Device freshDevice : devices) {
                    if (device.getPath().equals(freshDevice.getPath()) && device.getModel().equals(freshDevice.getModel())) {
                        device.setFingerprint(freshDevice.getFingerprint());
                    }
                }

                importXpub(derivation);
            });
            enumerateService.setOnFailed(workerStateEvent -> {
                setError("Error", enumerateService.getException().getMessage());
                importButton.setDisable(false);
            });
            enumerateService.start();
        } else {
            importXpub(derivation);
        }
    }

    private void importXpub(List<ChildNumber> derivation) {
        String derivationPath = KeyDerivation.writePath(derivation);

        Hwi.GetXpubService getXpubService = new Hwi.GetXpubService(device, passphrase.get(), derivationPath);
        getXpubService.setOnSucceeded(workerStateEvent -> {
            String xpub = getXpubService.getValue();

            try {
                Keystore keystore = new Keystore();
                keystore.setLabel(device.getModel().toDisplayString());
                keystore.setSource(KeystoreSource.HW_USB);
                keystore.setWalletModel(device.getModel());
                keystore.setKeyDerivation(new KeyDerivation(device.getFingerprint(), derivationPath));
                keystore.setExtendedPublicKey(ExtendedKey.fromDescriptor(xpub));

                importKeystore(derivation, keystore);
            } catch(Exception e) {
                setError("Could not retrieve xpub", e.getMessage());
            }
        });
        getXpubService.setOnFailed(workerStateEvent -> {
            setError("Could not retrieve xpub", getXpubService.getException().getMessage());
            importButton.setDisable(false);
        });
        setDescription("Importing...");
        showHideLink.setVisible(false);
        getXpubService.start();
    }

    private void importKeystore(List<ChildNumber> derivation, Keystore keystore) {
        if(wallet.getScriptType() == null) {
            ScriptType scriptType = Arrays.stream(ScriptType.ADDRESSABLE_TYPES).filter(type -> type.getDefaultDerivation().get(0).equals(derivation.get(0))).findFirst().orElse(ScriptType.P2PKH);
            wallet.setName(device.getModel().toDisplayString());
            wallet.setPolicyType(PolicyType.SINGLE);
            wallet.setScriptType(scriptType);
            wallet.getKeystores().add(keystore);
            wallet.setDefaultPolicy(Policy.getPolicy(PolicyType.SINGLE, scriptType, wallet.getKeystores(), null));

            EventManager.get().post(new WalletImportEvent(wallet));
        } else {
            EventManager.get().post(new KeystoreImportEvent(keystore));
        }
    }

    private void sign() {
        if(device.isCard()) {
            try {
                CardApi cardApi = CardApi.getCardApi(device.getModel(), pin.get());
                Service<PSBT> signService = cardApi.getSignService(wallet, psbt, messageProperty);
                handleCardOperation(signService, signButton, "Signing", true, event -> {
                    EventManager.get().post(new PSBTSignedEvent(psbt, signService.getValue()));
                });
            } catch(Exception e) {
                log.error("Signing Error: " + e.getMessage(), e);
                setError("Signing Error", e.getMessage());
                signButton.setDisable(false);
            }
        } else {
            Hwi.SignPSBTService signPSBTService = new Hwi.SignPSBTService(device, passphrase.get(), psbt,
                    OutputDescriptor.getOutputDescriptor(wallet), wallet.getFullName(), getDeviceRegistration());
            signPSBTService.setOnSucceeded(workerStateEvent -> {
                PSBT signedPsbt = signPSBTService.getValue();
                EventManager.get().post(new PSBTSignedEvent(psbt, signedPsbt));
                updateDeviceRegistrations(signPSBTService.getNewDeviceRegistrations());
            });
            signPSBTService.setOnFailed(workerStateEvent -> {
                setError("Signing Error", signPSBTService.getException().getMessage());
                log.error("Signing Error: " + signPSBTService.getException().getMessage(), signPSBTService.getException());
                signButton.setDisable(false);
            });
            setDescription("Signing...");
            showHideLink.setVisible(false);
            signPSBTService.start();
        }
    }

    private void handleCardOperation(Service<?> service, ButtonBase operationButton, String operationDescription, boolean pinRequired, EventHandler<WorkerStateEvent> successHandler) {
        if(pinRequired && pin.get().length() < device.getModel().getMinPinLength()) {
            setDescription(pin.get().isEmpty() ? "Enter PIN code" : "PIN code too short");
            setContent(getCardPinEntry(operationButton));
            showHideLink.setVisible(false);
            setExpanded(true);
            operationButton.setDisable(false);
            return;
        }

        service.setOnSucceeded(successHandler);
        service.setOnFailed(event -> {
            Throwable rootCause = Throwables.getRootCause(event.getSource().getException());
            if(rootCause instanceof CardAuthorizationException) {
                setError(rootCause.getMessage(), null);
                setContent(getCardPinEntry(operationButton));
            } else {
                log.error(operationDescription + " Error: " + rootCause.getMessage(), event.getSource().getException());
                setError(operationDescription + " Error", rootCause.getMessage());
            }
            operationButton.setDisable(false);
        });
        service.start();
    }

    private void displayAddress() {
        Hwi.DisplayAddressService displayAddressService = new Hwi.DisplayAddressService(device, passphrase.get(), wallet.getScriptType(), outputDescriptor,
                OutputDescriptor.getOutputDescriptor(wallet), wallet.getFullName(), getDeviceRegistration());
        displayAddressService.setOnSucceeded(successEvent -> {
            String address = displayAddressService.getValue();
            EventManager.get().post(new AddressDisplayedEvent(address));
            updateDeviceRegistrations(displayAddressService.getNewDeviceRegistrations());
        });
        displayAddressService.setOnFailed(failedEvent -> {
            setError("Could not display address", displayAddressService.getException().getMessage());
            displayAddressButton.setDisable(false);
        });
        setDescription("Check device for address");
        displayAddressService.start();
    }

    private byte[] getDeviceRegistration() {
        Optional<Keystore> optKeystore = wallet.getKeystores().stream()
                .filter(keystore -> keystore.getKeyDerivation().getMasterFingerprint().equals(device.getFingerprint()) && keystore.getDeviceRegistration() != null).findFirst();
        return optKeystore.map(Keystore::getDeviceRegistration).orElse(null);
    }

    private void updateDeviceRegistrations(Set<byte[]> newDeviceRegistrations) {
        if(!newDeviceRegistrations.isEmpty()) {
            List<Keystore> registrationKeystores = getDeviceRegistrationKeystores();
            if(!registrationKeystores.isEmpty()) {
                registrationKeystores.forEach(keystore -> keystore.setDeviceRegistration(newDeviceRegistrations.iterator().next()));
                EventManager.get().post(new KeystoreDeviceRegistrationsChangedEvent(wallet, registrationKeystores));
            }
        }
    }

    private List<Keystore> getDeviceRegistrationKeystores() {
        return wallet.getKeystores().stream().filter(keystore -> keystore.getKeyDerivation().getMasterFingerprint().equals(device.getFingerprint())).toList();
    }

    private void signMessage() {
        if(device.isCard()) {
            try {
                CardApi cardApi = CardApi.getCardApi(device.getModel(), pin.get());
                Service<String> signMessageService = cardApi.getSignMessageService(message, wallet.getScriptType(), keyDerivation.getDerivation(), messageProperty);
                handleCardOperation(signMessageService, signMessageButton, "Signing", true, event -> {
                    String signature = signMessageService.getValue();
                    EventManager.get().post(new MessageSignedEvent(wallet, signature));
                });
            } catch(Exception e) {
                log.error("Signing Error: " + e.getMessage(), e);
                setError("Signing Error", e.getMessage());
                signButton.setDisable(false);
            }
        } else {
            Hwi.SignMessageService signMessageService = new Hwi.SignMessageService(device, passphrase.get(), message, keyDerivation.getDerivationPath());
            signMessageService.setOnSucceeded(successEvent -> {
                String signature = signMessageService.getValue();
                EventManager.get().post(new MessageSignedEvent(wallet, signature));
            });
            signMessageService.setOnFailed(failedEvent -> {
                setError("Could not sign message", signMessageService.getException().getMessage());
                signMessageButton.setDisable(false);
            });
            setDescription("Signing message...");
            signMessageService.start();
        }
    }

    private void discoverKeystores() {
        if(wallet.getKeystores().size() != 1) {
            setError("Could not discover keystores", "Only single signature wallets are supported for keystore discovery");
            return;
        }

        String masterFingerprint = wallet.getKeystores().get(0).getKeyDerivation().getMasterFingerprint();

        Wallet copyWallet = wallet.copy();
        Map<StandardAccount, String> accountDerivationPaths = new LinkedHashMap<>();
        for(StandardAccount availableAccount : availableAccounts) {
            Wallet availableWallet = copyWallet.addChildWallet(availableAccount);
            Keystore availableKeystore = availableWallet.getKeystores().get(0);
            String derivationPath = availableKeystore.getKeyDerivation().getDerivationPath();
            accountDerivationPaths.put(availableAccount, derivationPath);
        }

        Map<StandardAccount, Keystore> importedKeystores = new LinkedHashMap<>();
        Hwi.GetXpubsService getXpubsService = new Hwi.GetXpubsService(device, passphrase.get(), accountDerivationPaths);
        getXpubsService.setOnSucceeded(workerStateEvent -> {
            Map<StandardAccount, String> accountXpubs = getXpubsService.getValue();

            for(Map.Entry<StandardAccount, String> entry : accountXpubs.entrySet()) {
                try {
                    Keystore keystore = new Keystore();
                    keystore.setLabel(device.getModel().toDisplayString());
                    keystore.setSource(KeystoreSource.HW_USB);
                    keystore.setWalletModel(device.getModel());
                    keystore.setKeyDerivation(new KeyDerivation(masterFingerprint, accountDerivationPaths.get(entry.getKey())));
                    keystore.setExtendedPublicKey(ExtendedKey.fromDescriptor(entry.getValue()));
                    importedKeystores.put(entry.getKey(), keystore);
                } catch(Exception e) {
                    setError("Could not retrieve xpub", e.getMessage());
                }
            }

            ElectrumServer.AccountDiscoveryService accountDiscoveryService = new ElectrumServer.AccountDiscoveryService(wallet, importedKeystores);
            accountDiscoveryService.setOnSucceeded(event -> {
                importedKeystores.keySet().retainAll(accountDiscoveryService.getValue());
                EventManager.get().post(new KeystoresDiscoveredEvent(importedKeystores));
            });
            accountDiscoveryService.setOnFailed(event -> {
                log.error("Failed to discover accounts", event.getSource().getException());
                setError("Failed to discover accounts", event.getSource().getException().getMessage());
                discoverKeystoresButton.setDisable(false);
            });
            accountDiscoveryService.start();
        });
        getXpubsService.setOnFailed(workerStateEvent -> {
            setError("Could not retrieve xpub", getXpubsService.getException().getMessage());
            discoverKeystoresButton.setDisable(false);
        });
        setDescription("Discovering...");
        showHideLink.setVisible(false);
        getXpubsService.start();
    }

    private void getPrivateKey(Integer slot) {
        if(device.isCard()) {
            try {
                CardApi cardApi = CardApi.getCardApi(device.getModel(), pin.get());
                Service<ECKey> privateKeyService = cardApi.getPrivateKeyService(slot, messageProperty);
                handleCardOperation(privateKeyService, getPrivateKeyButton, "Private Key", true, event -> {
                    EventManager.get().post(new DeviceGetPrivateKeyEvent(privateKeyService.getValue(), cardApi.getDefaultScriptType()));
                });
            } catch(Exception e) {
                log.error("Private Key Error: " + e.getMessage(), e);
                setError("Private Key Error", e.getMessage());
                getPrivateKeyButton.setDisable(false);
            }
        }
    }

    private void getAddress() {
        if(device.isCard()) {
            try {
                CardApi cardApi = CardApi.getCardApi(device.getModel(), pin.get());
                if(!cardApi.isInitialized()) {
                    if(pin.get().length() < device.getModel().getMinPinLength()) {
                        setDescription(pin.get().isEmpty() ? "Enter PIN code" : "PIN code too short");
                        setContent(getCardPinEntry(getAddressButton));
                        showHideLink.setVisible(false);
                        setExpanded(true);
                        getAddressButton.setDisable(false);
                        return;
                    }

                    setDescription("Card not initialized");
                    setContent(getCardInitializationPanel(cardApi, getAddressButton, DeviceOperation.GET_ADDRESS));
                    showHideLink.setVisible(false);
                    setExpanded(true);
                    return;
                }

                Service<Address> addressService = cardApi.getAddressService(messageProperty);
                handleCardOperation(addressService, getAddressButton, "Address", false, event -> {
                    EventManager.get().post(new DeviceAddressEvent(addressService.getValue()));
                });
            } catch(Exception e) {
                log.error("Address Error: " + e.getMessage(), e);
                setError("Address Error", e.getMessage());
                getAddressButton.setDisable(false);
            }
        }
    }

    private void showOperationButton() {
        if(deviceOperation.equals(DeviceOperation.IMPORT)) {
            if(defaultDevice) {
                importButton.getStyleClass().add("default-button");
            }
            importButton.setVisible(true);
            showHideLink.setText("Show derivation...");
            showHideLink.setVisible(!device.isCard());
            List<ChildNumber> defaultDerivation = wallet.getScriptType() == null ? ScriptType.P2WPKH.getDefaultDerivation() : wallet.getScriptType().getDefaultDerivation();
            setContent(getDerivationEntry(keyDerivation == null ? defaultDerivation : keyDerivation.getDerivation()));
        } else if(deviceOperation.equals(DeviceOperation.SIGN)) {
            signButton.setDefaultButton(defaultDevice);
            signButton.setVisible(true);
            showHideLink.setVisible(false);
        } else if(deviceOperation.equals(DeviceOperation.DISPLAY_ADDRESS)) {
            displayAddressButton.setDefaultButton(defaultDevice);
            displayAddressButton.setVisible(true);
            showHideLink.setVisible(false);
        } else if(deviceOperation.equals(DeviceOperation.SIGN_MESSAGE)) {
            signMessageButton.setDefaultButton(defaultDevice);
            signMessageButton.setVisible(true);
            showHideLink.setVisible(false);
        } else if(deviceOperation.equals(DeviceOperation.DISCOVER_KEYSTORES)) {
            discoverKeystoresButton.setDefaultButton(defaultDevice);
            discoverKeystoresButton.setVisible(true);
            showHideLink.setVisible(false);
        } else if(deviceOperation.equals(DeviceOperation.GET_PRIVATE_KEY)) {
            if(defaultDevice) {
                getPrivateKeyButton.getStyleClass().add("default-button");
            }
            getPrivateKeyButton.setVisible(true);
            showHideLink.setVisible(false);
        } else if(deviceOperation.equals(DeviceOperation.GET_ADDRESS)) {
            getAddressButton.setDefaultButton(defaultDevice);
            getAddressButton.setVisible(true);
            showHideLink.setVisible(false);
        }
    }

    private Node getDerivationEntry(List<ChildNumber> derivation) {
        TextField derivationField = new TextField();
        derivationField.setPromptText("Derivation path");
        derivationField.setText(KeyDerivation.writePath(derivation));
        derivationField.setDisable(device.isCard() || keyDerivation != null);
        HBox.setHgrow(derivationField, Priority.ALWAYS);

        ValidationSupport validationSupport = new ValidationSupport();
        validationSupport.setValidationDecorator(new StyleClassValidationDecoration());
        validationSupport.registerValidator(derivationField, Validator.combine(
                Validator.createEmptyValidator("Derivation is required"),
                (Control c, String newValue) -> ValidationResult.fromErrorIf( c, "Invalid derivation", !KeyDerivation.isValid(newValue))
        ));

        Button importDerivationButton = new Button("Import Custom Derivation");
        importDerivationButton.setDisable(true);
        importDerivationButton.setOnAction(event -> {
            showHideLink.setVisible(true);
            setExpanded(false);
            List<ChildNumber> importDerivation = KeyDerivation.parsePath(derivationField.getText());
            importXpub(importDerivation);
        });

        derivationField.textProperty().addListener((observable, oldValue, newValue) -> {
            importButton.setDisable(newValue.isEmpty() || !KeyDerivation.isValid(newValue) || !KeyDerivation.parsePath(newValue).equals(derivation));
            importDerivationButton.setDisable(newValue.isEmpty() || !KeyDerivation.isValid(newValue) || KeyDerivation.parsePath(newValue).equals(derivation));
        });

        HBox contentBox = new HBox();
        contentBox.setAlignment(Pos.TOP_RIGHT);
        contentBox.setSpacing(20);
        contentBox.getChildren().add(derivationField);
        contentBox.getChildren().add(importDerivationButton);
        contentBox.setPadding(new Insets(10, 30, 10, 30));
        contentBox.setPrefHeight(60);

        return contentBox;
    }

    private Node getCardInitializationPanel(CardApi cardApi, ButtonBase operationButton, DeviceOperation deviceOperation) {
        if(device.getModel().requiresSeedInitialization()) {
            return getCardSeedInitializationPanel(cardApi, operationButton, deviceOperation);
        }

        return getCardEntropyInitializationPanel(cardApi, operationButton, deviceOperation);
    }

    private Node getCardSeedInitializationPanel(CardApi cardApi, ButtonBase operationButton, DeviceOperation deviceOperation) {
        VBox confirmationBox = new VBox(5);
        CustomPasswordField confirmationPin = new ViewPasswordField();
        confirmationPin.setPromptText("Re-enter chosen PIN");
        confirmationBox.getChildren().add(confirmationPin);

        Button initializeButton = new Button("Initialize");
        initializeButton.setDefaultButton(true);
        initializeButton.setOnAction(event -> {
            initializeButton.setDisable(true);
            if(!pin.get().equals(confirmationPin.getText())) {
                setError("PIN Error", "The confirmation PIN did not match");
                return;
            }
            int pinSize = pin.get().length();
            if(pinSize < device.getModel().getMinPinLength() || pinSize > device.getModel().getMaxPinLength()) {
                setError("PIN Error", "PIN length must be between " + device.getModel().getMinPinLength() + " and " + device.getModel().getMaxPinLength() + " characters");
                return;
            }

            SeedEntryDialog seedEntryDialog = new SeedEntryDialog(device.getModel().toDisplayString() + " Seed Words", 12);
            seedEntryDialog.initOwner(this.getScene().getWindow());
            Optional<List<String>> optWords = seedEntryDialog.showAndWait();
            if(optWords.isPresent()) {
                try {
                    List<String> mnemonicWords = optWords.get();
                    Bip39MnemonicCode.INSTANCE.check(mnemonicWords);
                    DeterministicSeed seed = new DeterministicSeed(mnemonicWords, "", System.currentTimeMillis(), DeterministicSeed.Type.BIP39);
                    byte[] seedBytes = seed.getSeedBytes();

                    Service<Void> cardInitializationService = cardApi.getInitializationService(seedBytes, messageProperty);
                    cardInitializationService.setOnSucceeded(successEvent -> {
                        AppServices.showSuccessDialog("Card Initialized", "The card was successfully initialized.\n\nYou can now import the keystore.");
                        operationButton.setDisable(false);
                        setDefaultStatus();
                        setExpanded(false);
                    });
                    cardInitializationService.setOnFailed(failEvent -> {
                        log.error("Error initializing card", failEvent.getSource().getException());
                        AppServices.showErrorDialog("Card Initialization Failed", "The card was not initialized.\n\n" + failEvent.getSource().getException().getMessage());
                        initializeButton.setDisable(false);
                    });
                    cardInitializationService.start();
                } catch(MnemonicException e) {
                    log.error("Invalid seed entered", e);
                    AppServices.showErrorDialog("Invalid seed entered", "The seed was invalid.\n\n" + e.getMessage());
                    initializeButton.setDisable(false);
                }
            } else {
                initializeButton.setDisable(false);
            }
        });

        HBox contentBox = new HBox(20);
        contentBox.getChildren().addAll(confirmationBox, initializeButton);
        contentBox.setPadding(new Insets(10, 30, 10, 30));
        HBox.setHgrow(confirmationBox, Priority.ALWAYS);

        return contentBox;
    }

    private Node getCardEntropyInitializationPanel(CardApi cardApi, ButtonBase operationButton, DeviceOperation deviceOperation) {
        VBox initTypeBox = new VBox(5);
        RadioButton automatic = new RadioButton("Automatic (Recommended)");
        RadioButton advanced = new RadioButton("Advanced");
        TextField entropy = new TextField();
        entropy.setPromptText("Enter input for user entropy");
        entropy.setDisable(true);

        ToggleGroup toggleGroup = new ToggleGroup();
        automatic.setToggleGroup(toggleGroup);
        advanced.setToggleGroup(toggleGroup);
        automatic.setSelected(true);
        toggleGroup.selectedToggleProperty().addListener((observable, oldValue, newValue) -> {
            entropy.setDisable(newValue == automatic);
        });

        initTypeBox.getChildren().addAll(automatic, advanced, entropy);

        Button initializeButton = new Button("Initialize");
        initializeButton.setDefaultButton(true);
        initializeButton.setOnAction(event -> {
            initializeButton.setDisable(true);
            byte[] chainCode = toggleGroup.getSelectedToggle() == automatic ? null : Sha256Hash.hashTwice(entropy.getText().getBytes(StandardCharsets.UTF_8));
            Service<Void> cardInitializationService = cardApi.getInitializationService(chainCode, messageProperty);
            cardInitializationService.setOnSucceeded(successEvent -> {
                if(deviceOperation == DeviceOperation.IMPORT) {
                    AppServices.showSuccessDialog("Card Initialized", "The card was successfully initialized.\n\nYou can now import the keystore.");
                } else if(deviceOperation == DeviceOperation.GET_ADDRESS) {
                    AppServices.showSuccessDialog("Card Reinitialized", "The card was successfully reinitialized.\n\nYou can now retrieve the new deposit address.");
                }
                operationButton.setDisable(false);
                setDefaultStatus();
                setExpanded(false);
            });
            cardInitializationService.setOnFailed(failEvent -> {
                Throwable rootCause = Throwables.getRootCause(failEvent.getSource().getException());
                if(rootCause instanceof CardAuthorizationException) {
                    setError(rootCause.getMessage(), null);
                    setContent(getCardPinEntry(operationButton));
                    operationButton.setDisable(false);
                } else {
                    log.error("Error initializing card", failEvent.getSource().getException());
                    AppServices.showErrorDialog("Card Initialization Failed", "The card was not initialized.\n\n" + failEvent.getSource().getException().getMessage());
                    initializeButton.setDisable(false);
                }
            });
            cardInitializationService.start();
        });

        HBox contentBox = new HBox(20);
        contentBox.getChildren().addAll(initTypeBox, initializeButton);
        contentBox.setPadding(new Insets(10, 30, 10, 30));
        HBox.setHgrow(initTypeBox, Priority.ALWAYS);

        return contentBox;
    }

    private Node getCardPinEntry(ButtonBase operationButton) {
        VBox vBox = new VBox();

        CustomPasswordField pinField = new ViewPasswordField();
        pinField.setPromptText("PIN Code");
        if(operationButton instanceof Button defaultButton) {
            defaultButton.setDefaultButton(true);
        }
        pin.bind(pinField.textProperty());
        HBox.setHgrow(pinField, Priority.ALWAYS);
        Platform.runLater(pinField::requestFocus);

        HBox contentBox = new HBox();
        contentBox.setAlignment(Pos.TOP_RIGHT);
        contentBox.setSpacing(20);
        contentBox.getChildren().add(pinField);
        contentBox.setPadding(new Insets(10, 30, 10, 30));
        contentBox.setPrefHeight(50);

        vBox.getChildren().add(contentBox);

        return vBox;
    }

    public Device getDevice() {
        return device;
    }

    public enum DeviceOperation {
        IMPORT, SIGN, DISPLAY_ADDRESS, SIGN_MESSAGE, DISCOVER_KEYSTORES, GET_PRIVATE_KEY, GET_ADDRESS;
    }
}
