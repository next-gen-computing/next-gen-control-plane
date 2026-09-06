// Thin wrapper around EventSource: reconnects with backoff (the browser's built-in EventSource
// reconnect is instant-retry-forever, which is fine for a same-machine loopback server but still
// worth capping so a screen swap that closes the connection doesn't spin).
window.NG = window.NG || {};

NG.sse = {
    /**
     * @param path e.g. "/api/nodes/stream"
     * @param onMessage called with the parsed JSON payload of every frame
     * @returns a close() function — call it when the owning screen is torn down
     */
    subscribe(path, onMessage) {
        let source = null;
        let closed = false;

        function connect() {
            if (closed) return;
            source = new EventSource(path);
            source.onmessage = (event) => {
                try {
                    onMessage(JSON.parse(event.data));
                } catch (e) {
                    console.error('Malformed SSE frame on', path, e);
                }
            };
            source.onerror = () => {
                source.close();
                if (!closed) {
                    setTimeout(connect, 1000);
                }
            };
        }

        connect();

        return () => {
            closed = true;
            if (source) source.close();
        };
    }
};
