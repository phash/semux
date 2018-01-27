/**
 * Copyright (c) 2017-2018 The Semux Developers
 *
 * Distributed under the MIT software license, see the accompanying file
 * LICENSE or https://opensource.org/licenses/mit-license.php
 */
package org.semux.gui;

import javax.swing.JFrame;

public class BaseTestApplication extends MainFrame {

    private static final long serialVersionUID = 1L;

    public BaseTestApplication() {
        super(null);
        setTitle(getClass().getCanonicalName());
        setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
    }
}
