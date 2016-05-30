/*
 * Copyright 2014 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License, version 2.0 (the
 * "License"); you may not use this file except in compliance with the License. You may obtain a
 * copy of the License at:
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */

package io.netty.handler.codec.http2;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http2.internal.hpack.Encoder;
import io.netty.util.internal.UnstableApi;

import java.util.Map.Entry;

import static io.netty.handler.codec.http2.Http2CodecUtil.DEFAULT_HEADER_TABLE_SIZE;
import static io.netty.handler.codec.http2.Http2Error.COMPRESSION_ERROR;
import static io.netty.handler.codec.http2.Http2Error.PROTOCOL_ERROR;
import static io.netty.handler.codec.http2.Http2Exception.connectionError;
import static io.netty.util.internal.ObjectUtil.checkNotNull;

@UnstableApi
public class DefaultHttp2HeadersEncoder implements Http2HeadersEncoder, Http2HeadersEncoder.Configuration {
    private final Encoder encoder;
    private final SensitivityDetector sensitivityDetector;
    private final Http2HeaderTable headerTable;
    private final ByteBuf tableSizeChangeOutput = Unpooled.buffer();

    public DefaultHttp2HeadersEncoder() {
        this(DEFAULT_HEADER_TABLE_SIZE, NEVER_SENSITIVE);
    }

    public DefaultHttp2HeadersEncoder(int maxHeaderTableSize, SensitivityDetector sensitivityDetector) {
        this.sensitivityDetector = checkNotNull(sensitivityDetector, "sensitiveDetector");
        encoder = new Encoder(maxHeaderTableSize);
        headerTable = new Http2HeaderTableEncoder();
    }

    @Override
    public void encodeHeaders(Http2Headers headers, ByteBuf buffer) throws Http2Exception {
        try {
            if (headers.size() > headerTable.maxHeaderListSize()) {
                throw connectionError(PROTOCOL_ERROR, "Number of headers (%d) exceeds maxHeaderListSize (%d)",
                        headers.size(), headerTable.maxHeaderListSize());
            }

            // If there was a change in the table size, serialize the output from the encoder
            // resulting from that change.
            if (tableSizeChangeOutput.isReadable()) {
                buffer.writeBytes(tableSizeChangeOutput);
                tableSizeChangeOutput.clear();
            }

            for (Entry<CharSequence, CharSequence> header : headers) {
                encodeHeader(buffer, header.getKey(), header.getValue());
            }
        } catch (Http2Exception e) {
            throw e;
        } catch (Throwable t) {
            throw connectionError(COMPRESSION_ERROR, t, "Failed encoding headers block: %s", t.getMessage());
        }
    }

    @Override
    public Http2HeaderTable headerTable() {
        return headerTable;
    }

    @Override
    public Configuration configuration() {
        return this;
    }

    private void encodeHeader(ByteBuf out, CharSequence key, CharSequence value) {
        encoder.encodeHeader(out, key, value, sensitivityDetector.isSensitive(key, value));
    }

    /**
     * {@link Http2HeaderTable} implementation to support {@link Http2HeadersEncoder}
     */
    private final class Http2HeaderTableEncoder extends DefaultHttp2HeaderTableListSize implements Http2HeaderTable {
        @Override
        public void maxHeaderTableSize(int max) throws Http2Exception {
            if (max < 0) {
                throw connectionError(PROTOCOL_ERROR, "Header Table Size must be non-negative but was %d", max);
            }
            try {
                // No headers should be emitted. If they are, we throw.
                encoder.setMaxHeaderTableSize(tableSizeChangeOutput, max);
            } catch (Throwable t) {
                throw new Http2Exception(PROTOCOL_ERROR, t.getMessage(), t);
            }
        }

        @Override
        public int maxHeaderTableSize() {
            return encoder.getMaxHeaderTableSize();
        }
    }
}
