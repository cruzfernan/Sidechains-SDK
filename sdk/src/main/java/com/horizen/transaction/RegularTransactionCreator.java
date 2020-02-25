package com.horizen.transaction;

import com.horizen.box.Box;
import com.horizen.box.NoncedBox;
import com.horizen.box.RegularBox;
import com.horizen.box.data.NoncedBoxData;
import com.horizen.box.data.RegularBoxData;
import com.horizen.node.NodeWallet;
import com.horizen.proposition.Proposition;
import com.horizen.proposition.PublicKey25519Proposition;
import com.horizen.secret.PrivateKey25519;
import com.horizen.secret.Secret;

import com.horizen.utils.Pair;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;


public class RegularTransactionCreator {

    public static RegularTransaction create(NodeWallet wallet,
                                            List<NoncedBoxData<? extends Proposition, ? extends NoncedBox<? extends Proposition>>> to,
                                            PublicKey25519Proposition changeAddress,
                                            long fee,
                                            List<byte[]> boxIdsToExclude) {
        // 0. check parameters (fee >= 0, to.values >= 0, etc.)
        // 1. calculate sum of to.getValue(...) + fee
        // 2. get from wallet proper number of closed RegularBox ordered by creation time, which ids is not in boxIdsToExclude and sum of their values >= sum above
        // 3. set change to changeAddress if need
        // 4. construct inputs and outputs lists, timestamp
        // 5. try to do RegularTransaction.create(...)

        Objects.requireNonNull(wallet, "Wallet can't be null");
        Objects.requireNonNull(to, "Destination box data list can't be null");
        Objects.requireNonNull(changeAddress, "Change address can't be null");
        Objects.requireNonNull(boxIdsToExclude, "Box ids to exclude list can't be null");

        long toAmount = 0;
        for(NoncedBoxData boxData : to) {
            if (boxData.value() < 0)
                throw new IllegalArgumentException("Output values must be >= 0.");
            toAmount += boxData.value();
        }

        if (fee < 0)
            throw new IllegalArgumentException("Fee must be >= 0.");
        toAmount += fee;


        List<RegularBox> boxes = new ArrayList<>();
        for(Box box : wallet.boxesOfType(RegularBox.class, boxIdsToExclude)) {
            boxes.add((RegularBox) box);
        }

        List<Pair<RegularBox, PrivateKey25519>> from = new ArrayList<>();
        long currentAmount = 0;
        for(RegularBox box : boxes) {
            Secret s = wallet.secretByPublicKey(box.proposition()).get();
            if(s instanceof PrivateKey25519) {
                from.add(new Pair<>(box, (PrivateKey25519)s));
                currentAmount += box.value();
                if (currentAmount >= toAmount)
                    break;
            }
        }
        if(currentAmount < toAmount)
            throw new IllegalArgumentException("Not enough balances in the wallet to create a transaction.");

        // add change to outputs
        List<NoncedBoxData<? extends Proposition, ? extends NoncedBox<? extends Proposition>>> sendTo = new ArrayList<>(to);
        if(currentAmount > toAmount) {
            sendTo.add(new RegularBoxData(changeAddress, currentAmount - toAmount));
        }

        // NOTE: in HybridApp they use System.currentTimeMillis(). Is it a good solution?
        long timestamp = System.currentTimeMillis();
        return RegularTransaction.create(from, sendTo, fee, timestamp);
    }
}
