package com.sparrowwallet.sparrow.joinstr;

import com.sparrowwallet.sparrow.BaseController;
import com.sparrowwallet.sparrow.wallet.WalletForm;

public abstract class JoinstrFormController extends BaseController {

    private JoinstrController joinstrController;
    private JoinstrForm joinstrForm;

    public WalletForm getWalletForm() {
        return joinstrForm.getWalletForm();
    }

    public JoinstrForm getJoinstrForm() {
        return joinstrForm;
    }

    public JoinstrController getJoinstrController() {
        return joinstrController;
    }

    public void setJoinstrController(JoinstrController joinstrController) {
        this.joinstrController = joinstrController;
    }

    public void setJoinstrForm(JoinstrForm joinstrForm) {
        this.joinstrForm = joinstrForm;
    }

    public abstract void initializeView();

}
