/*
 * $Id$
 *
 * Firebird Open Source JavaEE Connector - JDBC Driver
 *
 * Distributable under LGPL license.
 * You may obtain a copy of the License at http://www.gnu.org/copyleft/lgpl.html
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * LGPL License for more details.
 *
 * This file was created by members of the firebird development team.
 * All individual contributions remain the Copyright (C) of those
 * individuals.  Contributors to this file are either listed here or
 * can be obtained from a source control history command.
 *
 * All rights reserved.
 */
package org.firebirdsql.gds.impl.jni;

import org.firebirdsql.gds.BlobParameterBuffer;
import org.firebirdsql.gds.ISCConstants;
import org.firebirdsql.gds.impl.argument.NumericArgument;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;

public class BlobParameterBufferImp extends ParameterBufferBase implements BlobParameterBuffer {

    @Override
    public void addArgument(int argumentType, int value) {
        if (value > 65535)
            throw new RuntimeException("Blob parameter buffer value out of range for type " + argumentType);

        getArgumentsList().add(new NumericArgument(argumentType, value) {
            @Override
            protected void writeValue(OutputStream outputStream, int value) throws IOException {
                outputStream.write(2);
                outputStream.write(value);
                outputStream.write(value >> 8);
            }
        });
    }

    /**
     * Method for obtaining buffer suitable for passing to native method.
     *
     * @return Buffer for native method
     */
    public byte[] getBytesForNativeCode() {
        final ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        byteArrayOutputStream.write(ISCConstants.isc_bpb_version1);

        try {
            super.writeArgumentsTo(byteArrayOutputStream);
        } catch (IOException e) {
            // Ignoring IOException, not thrown by ByteArrayOutputStream
        }

        return byteArrayOutputStream.toByteArray();
    }
}
