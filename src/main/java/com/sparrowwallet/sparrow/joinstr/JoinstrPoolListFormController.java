package com.sparrowwallet.sparrow.joinstr;

import com.sparrowwallet.sparrow.io.Config;
import com.sparrowwallet.sparrow.joinstr.control.JoinstrInfoPane;
import com.sparrowwallet.sparrow.joinstr.control.JoinstrPoolList;

import java.util.ArrayList;

public class JoinstrPoolListFormController extends JoinstrFormController {

    protected JoinstrPoolList joinstrPoolList;
    protected JoinstrInfoPane joinstrInfoPane;

    @Override
    public void initializeView() {
        try {
            joinstrPoolList = new JoinstrPoolList();

            // Add pool store data
            addPoolsData();

            joinstrInfoPane = new JoinstrInfoPane();
            joinstrInfoPane.initInfoPane();
            joinstrInfoPane.setVisible(false);
            joinstrInfoPane.setManaged(false);

            joinstrPoolList.setOnPoolSelectedListener(pool -> {
                if (pool != null) {
                    getJoinstrController().setSelectedPool(pool);
                    joinstrInfoPane.setVisible(true);
                    joinstrInfoPane.setManaged(true);
                    joinstrInfoPane.updatePoolInfo(pool);
                } else {
                    joinstrInfoPane.setVisible(false);
                    joinstrInfoPane.setManaged(false);
                }
            });

            JoinstrPool selectedPool = getJoinstrController().getSelectedPool();
            if(selectedPool != null) {
                joinstrPoolList.setSelectedPool(selectedPool);
            }

        } catch (Exception e) {
            if(e != null) {
                e.printStackTrace();
            }
        }

    }

    protected void addPoolsData() {
        ArrayList<JoinstrPool> pools = Config.get().getPoolStore();

        joinstrPoolList.clearPools();
        for (JoinstrPool pool: pools) {
            joinstrPoolList.addPool(pool);
        }
    }

}
