package org.voltdb.xdcrutil;


/* This file is part of VoltDB.
 * Copyright (C) 2008-2020 VoltDB Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining
 * a copy of this software and associated documentation files (the
 * "Software"), to deal in the Software without restriction, including
 * without limitation the rights to use, copy, modify, merge, publish,
 * distribute, sublicense, and/or sell copies of the Software, and to
 * permit persons to whom the Software is furnished to do so, subject to
 * the following conditions:
 *
 * The above copyright notice and this permission notice shall be
 * included in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
 * EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
 * MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT.
 * IN NO EVENT SHALL THE AUTHORS BE LIABLE FOR ANY CLAIM, DAMAGES OR
 * OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE,
 * ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR
 * OTHER DEALINGS IN THE SOFTWARE.
 */


import org.voltdb.client.ClientResponse;
import org.voltdb.client.ProcedureCallback;
import org.voltdb.xdcrdatagen.ConflictGenerator;

/**
 * Simple callback that complains if something went badly
 * wrong.
 */
public class CheckChangeCountsCallback implements ProcedureCallback {

    @Override
    public void clientCallback(ClientResponse arg0) throws Exception {
        
        if (arg0.getStatus() != ClientResponse.SUCCESS) {
            ConflictGenerator.msg("Error Code " + arg0.getStatusString());
        } else {
            if (arg0.getResults().length != 2) {
                ConflictGenerator.msg("Bad Results for " + arg0.toString());
            } else {
                arg0.getResults()[0].advanceRow();
                arg0.getResults()[1].advanceRow();
                long rejected = arg0.getResults()[0].getLong("rejected");
                long accepted = arg0.getResults()[1].getLong("accepted");
                
                if (accepted != rejected) {
                    ConflictGenerator.msg("Non matching results: " + accepted + " != " + rejected + " " + arg0.toString());
                }
            }
        }
        
        

    }

}
