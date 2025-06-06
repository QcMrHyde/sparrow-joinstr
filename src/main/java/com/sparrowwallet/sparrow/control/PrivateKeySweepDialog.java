package com.sparrowwallet.sparrow.control;

import com.google.common.base.Throwables;
import com.google.common.eventbus.Subscribe;
import com.google.common.io.Files;
import com.sparrowwallet.drongo.KeyPurpose;
import com.sparrowwallet.drongo.address.Address;
import com.sparrowwallet.drongo.address.InvalidAddressException;
import com.sparrowwallet.drongo.crypto.BIP38;
import com.sparrowwallet.drongo.crypto.DumpedPrivateKey;
import com.sparrowwallet.drongo.crypto.ECKey;
import com.sparrowwallet.drongo.policy.PolicyType;
import com.sparrowwallet.drongo.protocol.*;
import com.sparrowwallet.drongo.psbt.PSBT;
import com.sparrowwallet.drongo.psbt.PSBTInput;
import com.sparrowwallet.drongo.wallet.Wallet;
import com.sparrowwallet.drongo.wallet.WalletModel;
import com.sparrowwallet.sparrow.AppServices;
import com.sparrowwallet.sparrow.EventManager;
import com.sparrowwallet.sparrow.UnitFormat;
import com.sparrowwallet.sparrow.event.FeeRatesUpdatedEvent;
import com.sparrowwallet.sparrow.glyphfont.FontAwesome5;
import com.sparrowwallet.sparrow.io.CardApi;
import com.sparrowwallet.sparrow.io.Config;
import com.sparrowwallet.sparrow.net.ElectrumServer;
import com.sparrowwallet.sparrow.net.ServerType;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.event.ActionEvent;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import javafx.util.StringConverter;
import org.controlsfx.control.textfield.CustomPasswordField;
import org.controlsfx.glyphfont.Glyph;
import org.controlsfx.validation.ValidationResult;
import org.controlsfx.validation.ValidationSupport;
import org.controlsfx.validation.decoration.StyleClassValidationDecoration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tornadofx.control.Field;
import tornadofx.control.Fieldset;
import tornadofx.control.Form;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

import static com.sparrowwallet.drongo.protocol.ScriptType.P2TR;

public class PrivateKeySweepDialog extends Dialog<Transaction> {
    private static final Logger log = LoggerFactory.getLogger(PrivateKeySweepDialog.class);

    private final TextArea key;
    private final ComboBox<ScriptType> keyScriptType;
    private final CopyableLabel keyAddress;
    private final CopyableLabel keyUtxos;
    private final ComboBoxTextField toAddress;
    private final ComboBox<Wallet> toWallet;
    private final FeeRangeSlider feeRange;
    private final CopyableLabel feeRate;

    public PrivateKeySweepDialog(Wallet wallet) {
        final DialogPane dialogPane = getDialogPane();
        dialogPane.getStylesheets().add(AppServices.class.getResource("general.css").toExternalForm());
        dialogPane.getStylesheets().add(AppServices.class.getResource("dialog.css").toExternalForm());
        AppServices.setStageIcon(dialogPane.getScene().getWindow());
        dialogPane.setHeaderText("Sweep Private Key");
        dialogPane.setGraphic(new WalletModelImage(WalletModel.SEED));

        Form form = new Form();
        Fieldset fieldset = new Fieldset();
        fieldset.setText("");
        fieldset.setSpacing(10);

        Field keyField = new Field();
        keyField.setText("Private Key:");
        key = new TextArea();
        key.setWrapText(true);
        key.setPromptText("Wallet Import Format (WIF) or BIP38 encrypted key");
        key.setPrefRowCount(2);
        key.getStyleClass().add("fixed-width");
        HBox keyBox = new HBox(5);
        VBox keyButtonBox = new VBox(5);
        Button scanKey = new Button("", getGlyph(FontAwesome5.Glyph.CAMERA));
        scanKey.setOnAction(event -> scanPrivateKey());
        Button readKey = new Button("", getGlyph(FontAwesome5.Glyph.FILE_IMPORT));
        readKey.setOnAction(event -> readPrivateKey());
        keyButtonBox.getChildren().addAll(scanKey, readKey);
        keyBox.getChildren().addAll(key, keyButtonBox);
        HBox.setHgrow(key, Priority.ALWAYS);
        keyField.getInputs().add(keyBox);

        if(CardApi.isReaderAvailable()) {
            VBox cardButtonBox = new VBox(5);
            Button cardKey = new Button("", getGlyph(FontAwesome5.Glyph.WIFI));
            cardKey.setOnAction(event -> unsealPrivateKey());
            cardButtonBox.getChildren().add(cardKey);
            keyBox.getChildren().add(cardButtonBox);
        }

        Field keyScriptTypeField = new Field();
        keyScriptTypeField.setText("Script Type:");
        keyScriptType = new ComboBox<>();
        keyScriptType.setItems(FXCollections.observableList(ScriptType.getAddressableScriptTypes(PolicyType.SINGLE)));
        keyScriptTypeField.getInputs().add(keyScriptType);

        keyScriptType.setConverter(new StringConverter<ScriptType>() {
            @Override
            public String toString(ScriptType scriptType) {
                return scriptType == null ? "" : scriptType.getDescription();
            }

            @Override
            public ScriptType fromString(String string) {
                return null;
            }
        });

        Field addressField = new Field();
        addressField.setText("Address:");
        keyAddress = new CopyableLabel();
        keyAddress.getStyleClass().add("fixed-width");
        addressField.getInputs().add(keyAddress);

        Field utxosField = new Field();
        utxosField.setText("UTXOs:");
        keyUtxos = new CopyableLabel();
        utxosField.getInputs().add(keyUtxos);


        Field toAddressField = new Field();
        toAddressField.setText("Sweep to:");
        toAddress = new ComboBoxTextField();
        toAddress.getStyleClass().add("fixed-width");
        toWallet = new ComboBox<>();
        toWallet.setItems(FXCollections.observableList(AppServices.get().getOpenWallets().keySet().stream()
                .filter(w -> !w.isWhirlpoolChildWallet() && !w.isBip47()).collect(Collectors.toList())));
        toAddress.setComboProperty(toWallet);
        toWallet.prefWidthProperty().bind(toAddress.widthProperty());
        StackPane stackPane = new StackPane();
        stackPane.getChildren().addAll(toWallet, toAddress);
        toAddressField.getInputs().add(stackPane);

        Field feeRangeField = new Field();
        feeRangeField.setText("Fee range:");
        feeRange = new FeeRangeSlider();
        feeRange.setMaxWidth(320);
        feeRangeField.getInputs().add(feeRange);

        Field feeRateField = new Field();
        feeRateField.setText("Fee rate:");
        feeRate = new CopyableLabel();
        feeRateField.getInputs().add(feeRate);

        feeRange.valueProperty().addListener((observable, oldValue, newValue) -> {
            updateFeeRate();
        });
        feeRange.setFeeRate(AppServices.getDefaultFeeRate());
        updateFeeRate();

        fieldset.getChildren().addAll(keyField, keyScriptTypeField, addressField, toAddressField, feeRangeField, feeRateField);
        form.getChildren().add(fieldset);
        dialogPane.setContent(form);

        ButtonType createButtonType = new javafx.scene.control.ButtonType("Create Transaction", ButtonBar.ButtonData.APPLY);
        ButtonType cancelButtonType = new javafx.scene.control.ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);

        dialogPane.getButtonTypes().addAll(cancelButtonType, createButtonType);

        Button createButton = (Button) dialogPane.lookupButton(createButtonType);
        createButton.setDefaultButton(true);
        createButton.setDisable(true);
        createButton.addEventFilter(ActionEvent.ACTION, event -> {
            createTransaction();
            event.consume();
        });

        key.textProperty().addListener((observable, oldValue, newValue) -> {
            if(isEncryptedKey()) {
                decryptKey();
            }

            boolean isValidKey = isValidKey();
            createButton.setDisable(!isValidKey || !isValidToAddress());
            setScriptTypes(isValidKey);
            if(isValidKey) {
                setFromAddress();
            }
        });

        keyScriptType.valueProperty().addListener((observable, oldValue, newValue) -> {
            if(isValidKey()) {
                setFromAddress();
            }
        });

        toAddress.textProperty().addListener((observable, oldValue, newValue) -> {
            createButton.setDisable(!isValidKey() || !isValidToAddress());
        });

        toWallet.valueProperty().addListener((observable, oldValue, selectedWallet) -> {
            if(selectedWallet != null) {
                toAddress.setText(selectedWallet.getFreshNode(KeyPurpose.RECEIVE).getAddress().toString());
            }
        });

        keyScriptType.setValue(ScriptType.P2PKH);
        if(wallet != null) {
            toAddress.setText(wallet.getFreshNode(KeyPurpose.RECEIVE).getAddress().toString());
        }

        AppServices.onEscapePressed(dialogPane.getScene(), () -> setResult(null));
        AppServices.moveToActiveWindowScreen(this);
        setResultConverter(dialogButton -> null);
        dialogPane.setPrefWidth(680);

        EventManager.get().register(this);
        setOnCloseRequest(event -> {
            EventManager.get().unregister(this);
        });

        ValidationSupport validationSupport = new ValidationSupport();
        Platform.runLater(() -> {
            validationSupport.setValidationDecorator(new StyleClassValidationDecoration());
            validationSupport.registerValidator(key, (Control c, String newValue) -> ValidationResult.fromErrorIf(c, "Invalid private Key", !key.getText().isEmpty() && !isValidKey()));
            validationSupport.registerValidator(toAddress, (Control c, String newValue) -> ValidationResult.fromErrorIf(c, "Invalid address", !toAddress.getText().isEmpty() && !isValidToAddress()));
        });
    }

    private boolean isValidKey() {
        try {
            DumpedPrivateKey privateKey = getPrivateKey();
            return true;
        } catch(Exception e) {
            return false;
        }
    }

    private boolean isEncryptedKey() {
        return key.getText().length() == 58 && key.getText().startsWith("6P");
    }

    private void decryptKey() {
        PassphraseDialog passphraseDialog = new PassphraseDialog();
        passphraseDialog.initOwner(getDialogPane().getScene().getWindow());
        Optional<String> optPassphrase = passphraseDialog.showAndWait();
        if(optPassphrase.isPresent()) {
            try {
                DumpedPrivateKey decryptedKey = BIP38.decrypt(optPassphrase.get(), key.getText());
                Platform.runLater(() -> key.setText(decryptedKey.toString()));
            } catch(Exception e) {
                log.error("Failed to decrypt BIP38 key", e);
                AppServices.showErrorDialog("Failed to decrypt BIP38 key", e.getMessage());
                Platform.runLater(() -> key.setText(""));
            }
        } else {
            Platform.runLater(() -> key.setText(""));
        }
    }

    private DumpedPrivateKey getPrivateKey() {
        return DumpedPrivateKey.fromBase58(key.getText());
    }

    private boolean isValidToAddress() {
        try {
            Address address = getToAddress();
            return true;
        } catch (InvalidAddressException e) {
            return false;
        }
    }

    private Address getToAddress() throws InvalidAddressException {
        return Address.fromString(toAddress.getText());
    }

    private void setFromAddress() {
        DumpedPrivateKey privateKey = getPrivateKey();
        ScriptType scriptType = keyScriptType.getValue();
        Address address = scriptType.getAddress(privateKey.getKey());
        keyAddress.setText(address.toString());
    }

    private void setScriptTypes(boolean isValidKey) {
        boolean compressed = !isValidKey || getPrivateKey().getKey().isCompressed();
        if(compressed && !keyScriptType.getItems().equals(ScriptType.getAddressableScriptTypes(PolicyType.SINGLE))) {
            keyScriptType.getItems().addAll(ScriptType.getAddressableScriptTypes(PolicyType.SINGLE).stream().filter(s -> !keyScriptType.getItems().contains(s)).collect(Collectors.toList()));
        } else if(!compressed && !keyScriptType.getItems().equals(List.of(ScriptType.P2PKH))) {
            keyScriptType.getSelectionModel().select(0);
            keyScriptType.getItems().removeIf(scriptType -> scriptType != ScriptType.P2PKH);
        }
    }

    private void scanPrivateKey() {
        QRScanDialog qrScanDialog = new QRScanDialog();
        qrScanDialog.initOwner(getDialogPane().getScene().getWindow());
        Optional<QRScanDialog.Result> result = qrScanDialog.showAndWait();
        if(result.isPresent() && result.get().payload != null) {
            key.setText(result.get().payload);
        }
    }

    private void readPrivateKey() {
        Stage window = new Stage();
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Open Private Key File");

        AppServices.moveToActiveWindowScreen(window, 800, 450);
        File file = fileChooser.showOpenDialog(window);
        if(file != null) {
            if(file.length() > 1024) {
                AppServices.showErrorDialog("Invalid private key file", "This file does not contain a valid private key.");
                return;
            }

            try {
                key.setText(Files.asCharSource(file, StandardCharsets.UTF_8).read().trim());
            } catch(IOException e) {
                AppServices.showErrorDialog("Error reading private key file", e.getMessage());
            }
        }
    }

    private void unsealPrivateKey() {
        DeviceUnsealDialog deviceUnsealDialog = new DeviceUnsealDialog(Collections.emptyList());
        deviceUnsealDialog.initOwner(getDialogPane().getScene().getWindow());
        Optional<DeviceUnsealDialog.DevicePrivateKey> optPrivateKey = deviceUnsealDialog.showAndWait();
        if(optPrivateKey.isPresent()) {
            DeviceUnsealDialog.DevicePrivateKey devicePrivateKey = optPrivateKey.get();
            key.setText(devicePrivateKey.privateKey().getPrivateKeyEncoded().toBase58());
            keyScriptType.setValue(devicePrivateKey.scriptType());
        }
    }

    private void createTransaction() {
        try {
            DumpedPrivateKey privateKey = getPrivateKey();
            ScriptType scriptType = keyScriptType.getValue();
            Address fromAddress = scriptType.getAddress(privateKey.getKey());
            Address destAddress = getToAddress();

            Date since = null;
            if(Config.get().getServerType() == ServerType.BITCOIN_CORE) {
                WalletBirthDateDialog addressScanDateDialog = new WalletBirthDateDialog(null, true);
                addressScanDateDialog.initOwner(getDialogPane().getScene().getWindow());
                Optional<Date> optSince = addressScanDateDialog.showAndWait();
                if(optSince.isPresent()) {
                    since = optSince.get();
                } else {
                    return;
                }
            }

            ElectrumServer.AddressUtxosService addressUtxosService = new ElectrumServer.AddressUtxosService(fromAddress, since);
            addressUtxosService.setOnSucceeded(successEvent -> {
                createTransaction(privateKey.getKey(), scriptType, addressUtxosService.getValue(), destAddress);
            });
            addressUtxosService.setOnFailed(failedEvent -> {
                Throwable rootCause = Throwables.getRootCause(failedEvent.getSource().getException());
                log.error("Error retrieving outputs for address " + fromAddress, failedEvent.getSource().getException());
                AppServices.showErrorDialog("Error retrieving outputs for address", rootCause.getMessage());
            });

            if(Config.get().getServerType() == ServerType.BITCOIN_CORE) {
                ServiceProgressDialog serviceProgressDialog = new ServiceProgressDialog("Address Scan", "Scanning address for transactions...", new DialogImage(DialogImage.Type.SPARROW), addressUtxosService);
                serviceProgressDialog.initOwner(getDialogPane().getScene().getWindow());
                AppServices.moveToActiveWindowScreen(serviceProgressDialog);
            }

            addressUtxosService.start();
        } catch(Exception e) {
            log.error("Error creating sweep transaction", e);
        }
    }

    private void createTransaction(ECKey privKey, ScriptType scriptType, List<TransactionOutput> txOutputs, Address destAddress) {
        ECKey pubKey = ECKey.fromPublicOnly(privKey);

        Transaction noFeeTransaction = new Transaction();
        long total = 0;
        for(TransactionOutput txOutput : txOutputs) {
            scriptType.addSpendingInput(noFeeTransaction, txOutput, pubKey, TransactionSignature.dummy(scriptType == P2TR ? TransactionSignature.Type.SCHNORR : TransactionSignature.Type.ECDSA));
            total += txOutput.getValue();
        }

        TransactionOutput sweepOutput = new TransactionOutput(noFeeTransaction, total, destAddress.getOutputScript());
        noFeeTransaction.addOutput(sweepOutput);

        double feeRate = feeRange.getFeeRate();
        long fee = (long)Math.ceil(noFeeTransaction.getVirtualSize() * feeRate);
        if(feeRate == Transaction.DEFAULT_MIN_RELAY_FEE) {
            fee++;
        }

        long dustThreshold = destAddress.getScriptType().getDustThreshold(sweepOutput, Transaction.DUST_RELAY_TX_FEE);
        if(total - fee <= dustThreshold) {
            feeRate = Transaction.DEFAULT_MIN_RELAY_FEE;
            fee = (long)Math.ceil(noFeeTransaction.getVirtualSize() * feeRate) + 1;

            if(total - fee <= dustThreshold) {
                AppServices.showErrorDialog("Insufficient funds", "The unspent outputs for this private key contain insufficient funds to spend (" + total + " sats).");
                return;
            } else {
                Optional<ButtonType> optType = AppServices.showWarningDialog("Insufficient funds", "The unspent outputs for this private key contain insufficient funds (" + total + " sats) for a transaction at this fee rate." +
                        "\n\nContinue with a minimum fee rate transaction?", ButtonType.YES, ButtonType.NO);
                if(optType.isPresent() && optType.get() == ButtonType.NO) {
                    return;
                }
            }
        }

        Transaction transaction = new Transaction();
        transaction.setVersion(2);
        transaction.setLocktime(AppServices.getCurrentBlockHeight() == null ? 0 : AppServices.getCurrentBlockHeight());
        for(TransactionInput txInput : noFeeTransaction.getInputs()) {
            transaction.addInput(txInput);
        }
        transaction.addOutput(new TransactionOutput(transaction, total - fee, destAddress.getOutputScript()));

        PSBT psbt = new PSBT(transaction);
        //Set witness UTXOs on PSBT inputs first - they are all required when hashing for a Taproot signature
        for(int i = 0; i < txOutputs.size(); i++) {
            TransactionOutput utxoOutput = txOutputs.get(i);
            PSBTInput psbtInput = psbt.getPsbtInputs().get(i);
            psbtInput.setWitnessUtxo(utxoOutput);
        }

        for(int i = 0; i < txOutputs.size(); i++) {
            TransactionOutput utxoOutput = txOutputs.get(i);
            TransactionInput txInput = transaction.getInputs().get(i);
            PSBTInput psbtInput = psbt.getPsbtInputs().get(i);

            if(ScriptType.P2SH.isScriptType(utxoOutput.getScript())) {
                psbtInput.setRedeemScript(txInput.getScriptSig().getFirstNestedScript());
            }

            if(txInput.getWitness() != null) {
                psbtInput.setWitnessScript(txInput.getWitness().getWitnessScript());
            }

            if(!psbtInput.sign(scriptType.getOutputKey(privKey))) {
                AppServices.showErrorDialog("Failed to sign", "Failed to sign for transaction output " + utxoOutput.getHash() + ":" + utxoOutput.getIndex());
                return;
            }

            TransactionSignature signature = psbtInput.isTaproot() ? psbtInput.getTapKeyPathSignature() : psbtInput.getPartialSignature(pubKey);

            Transaction finalizeTransaction = new Transaction();
            TransactionInput finalizedTxInput = scriptType.addSpendingInput(finalizeTransaction, utxoOutput, pubKey, signature);
            psbtInput.setFinalScriptSig(finalizedTxInput.getScriptSig());
            psbtInput.setFinalScriptWitness(finalizedTxInput.getWitness());
        }

        setResult(psbt.extractTransaction());
    }

    public Glyph getGlyph(FontAwesome5.Glyph glyphEnum) {
        Glyph glyph = new Glyph(FontAwesome5.FONT_NAME, glyphEnum);
        glyph.setFontSize(12);
        return glyph;
    }

    private void updateFeeRate() {
        UnitFormat format = Config.get().getUnitFormat() == null ? UnitFormat.DOT : Config.get().getUnitFormat();
        feeRate.setText(format.getCurrencyFormat().format(feeRange.getFeeRate()) + " sats/vB");
    }

    @Subscribe
    public void feeRatesUpdated(FeeRatesUpdatedEvent event) {
        feeRange.updateTrackHighlight();
    }

    public class PassphraseDialog extends Dialog<String> {
        private final CustomPasswordField passphrase;

        public PassphraseDialog() {
            this.passphrase = new ViewPasswordField();

            final DialogPane dialogPane = getDialogPane();
            setTitle("BIP38 Passphrase");
            dialogPane.setHeaderText("Enter the BIP38 passphrase for this key:");
            dialogPane.getStylesheets().add(AppServices.class.getResource("general.css").toExternalForm());
            AppServices.setStageIcon(dialogPane.getScene().getWindow());
            dialogPane.getButtonTypes().addAll(ButtonType.CANCEL, ButtonType.OK);
            dialogPane.setPrefWidth(380);
            dialogPane.setPrefHeight(200);
            AppServices.moveToActiveWindowScreen(this);

            Glyph key = new Glyph(FontAwesome5.FONT_NAME, FontAwesome5.Glyph.KEY);
            key.setFontSize(50);
            dialogPane.setGraphic(key);

            final VBox content = new VBox(10);
            content.setPrefHeight(50);
            content.getChildren().add(passphrase);

            dialogPane.setContent(content);
            Platform.runLater(passphrase::requestFocus);

            setResultConverter(dialogButton -> dialogButton == ButtonType.OK ? passphrase.getText() : null);
        }
    }
}
