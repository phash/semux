/**
 * Copyright (c) 2017 The Semux Developers
 *
 * Distributed under the MIT software license, see the accompanying file
 * LICENSE or https://opensource.org/licenses/mit-license.php
 */
package org.semux.api.validation;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.junit.MockitoJUnitRunner;
import org.semux.config.DevNetConfig;
import org.semux.core.Unit;
import org.semux.crypto.CryptoException;
import org.semux.crypto.Hex;
import org.semux.gui.model.WalletAccount;

@RunWith(MockitoJUnitRunner.class)
public class ValidationsTest {

    @Test
    public void testMemoSizeMax() {
        String exact = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
        DevNetConfig config = mock(DevNetConfig.class);
        when(config.maxTransferDataSize()).thenReturn(128);
        assertTrue(Validations.validateDataLength(exact, config));
    }

    @Test
    public void testMemoSizeTooLong() {
        String tooLong = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

        DevNetConfig config = mock(DevNetConfig.class);
        when(config.maxTransferDataSize()).thenReturn(128);
        assertFalse(Validations.validateDataLength(tooLong, config));
    }

    @Test
    public void testMemoSizeShort() {
        String normal = "Memo Message";

        DevNetConfig config = mock(DevNetConfig.class);
        when(config.maxTransferDataSize()).thenReturn(128);
        assertTrue(Validations.validateDataLength(normal, config));

        String empty = "";
        assertTrue(Validations.validateDataLength(empty, config));

        // Null is allowed
        String nullMessage = null;
        assertTrue(Validations.validateDataLength(nullMessage, config));
    }

    @Test
    public void testValidateAddressLength() {
        String address = "02c5f1794d69717d538bfac150644f7d85945c12";

        assertTrue(Validations.validateAddressLength(Hex.decode(address)));
        assertFalse(Validations.validateAddressLength(Hex.decode(address + "1234567890")));
        assertFalse(Validations.validateAddressLength(Hex.decode("1234567890")));
    }

    @Test(expected = CryptoException.class)
    public void testValidateAddressException() {
        String address = "0x02c5f1794d69717d538bfac150644f7d85945c12";
        Hex.decode(address);

        Validations.validateAddressLength(Hex.decode(address + "1"));
    }

    @Test
    public void validateEnoughBalanceAvailable() {
        long value = 100000000;
        long fee = 5000000;
        WalletAccount acc = mock(WalletAccount.class);
        when(acc.getAvailable()).thenReturn(value + fee);
        assertTrue(Validations.validateSufficientBalanceAvailable(acc, value, fee));

        when(acc.getAvailable()).thenReturn(value);
        assertFalse(Validations.validateSufficientBalanceAvailable(acc, value, fee));

        when(acc.getAvailable()).thenReturn(value + fee + 1L);
        assertTrue(Validations.validateSufficientBalanceAvailable(acc, value, fee));
    }

    @Test
    public void validateFeeHighEnough() {
        long fee = 50L * Unit.MILLI_SEM;
        DevNetConfig config = mock(DevNetConfig.class);
        when(config.minTransactionFee()).thenReturn(fee);
        assertTrue(Validations.validateFeeHighEnough(fee, config));
        assertTrue(Validations.validateFeeHighEnough(fee + 1L, config));
        assertFalse(Validations.validateFeeHighEnough(fee - 1L, config));
    }

    @Test
    public void validatePositiveAmount() {
        assertTrue(Validations.validatePositiveAmountToSend(11000L));
        assertTrue(Validations.validatePositiveAmountToSend(0L));
        assertFalse(Validations.validatePositiveAmountToSend(-10L));
    }
}
