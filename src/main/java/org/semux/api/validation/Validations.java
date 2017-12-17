/**
 * Copyright (c) 2017 The Semux Developers
 *
 * Distributed under the MIT software license, see the accompanying file
 * LICENSE or https://opensource.org/licenses/mit-license.php
 */
package org.semux.api.validation;

import org.semux.config.Config;
import org.semux.crypto.EdDSA;
import org.semux.gui.model.WalletAccount;
import org.semux.util.Bytes;

public class Validations {

    /**
     * validates the length of a memo message
     * 
     * @param memo
     * @return true if memo is in bounds
     */
    public static boolean validateDataLength(String memo, Config config) {
        return null == memo || Bytes.of(memo).length <= config.maxTransferDataSize();
    }

    /**
     * Validates the length of an address TODO: check for a better validation of
     * addresses
     * 
     * @param to
     *            address
     * @return true if the size is correct
     */
    public static boolean validateAddressLength(byte[] to) {
        return  to.length == EdDSA.ADDRESS_LEN;
    }

    /**
     * Validates that there is enough balance to actually send the transaction
     * 
     * @param acc
     * @param value
     * @param fee
     * @return
     */
    public static boolean validateSufficientBalanceAvailable(WalletAccount acc, long value, long fee) {
        return value + fee <= acc.getAvailable();
    }

    /**
     * Validates the height of the fee
     * 
     * @param fee
     * @param config
     * @return
     */
    public static boolean validateFeeHighEnough(long fee, Config config) {
        return fee >= config.minTransactionFee();
    }

    /**
     * Validates that only positive amounts are sent
     * 
     * @param value
     * @return
     */
    public static boolean validatePositiveAmountToSend(long value) {
        return value >= 0L;
    }

   
}
