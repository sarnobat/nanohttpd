import static com.google.common.base.Preconditions.checkNotNull;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.ByteArrayInputStream;
import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.PushbackInputStream;
import java.io.RandomAccessFile;
import java.io.UnsupportedEncodingException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.StringTokenizer;
import java.util.TimeZone;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLServerSocketFactory;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

// Make everything static
// TODO: Make things private, not protected
// Try and make field variables constants by deprecating them

public class SridharFunctionalWebServer 
{
    private static final String HOST = "localhost";

	private static final int PORT_NUMBER = 8080;

    private interface AsyncRunner {

        void closeAll();

        void closed(ClientHandler clientHandler);

        void exec(ClientHandler code);
    }

    /**
     * The runnable that will be used for every new client connection.
     */
    private static class ClientHandler implements Runnable {

        private final InputStream inputStream;

        private final Socket acceptSocket;
        private final TempFileManagerFactory tempFileManagerFactory;
        private final AsyncRunner asyncRunner;
        private final Map<String, WebServerPlugin> mimeTypeHandlers;
        private final boolean quiet;
        private final List<File> rootDirs;
        
		private ClientHandler(InputStream inputStream, Socket acceptSocket,
				TempFileManagerFactory tempFileManagerFactory,
				AsyncRunner asyncRunner, Map<String, WebServerPlugin> mimeTypeHandlers,
				boolean quiet, List<File> rootDirs) {
            this.inputStream = inputStream;
            this.acceptSocket = acceptSocket;
            this.tempFileManagerFactory = tempFileManagerFactory;
            this.asyncRunner = asyncRunner;
            this.mimeTypeHandlers = mimeTypeHandlers;
            this.quiet = quiet;
            this.rootDirs = rootDirs;
        }

        public void close() {
            safeClose(this.inputStream);
            safeClose(this.acceptSocket);
        }

        @Override
        public void run() {
            OutputStream outputStream = null;
            try {
                outputStream = this.acceptSocket.getOutputStream();
                TempFileManager tempFileManager = this.tempFileManagerFactory.create();
                HTTPSession session = new HTTPSession(tempFileManager, this.inputStream, outputStream, this.acceptSocket.getInetAddress());
                while (!this.acceptSocket.isClosed()) {
                    session.execute(mimeTypeHandlers, this.quiet, rootDirs);
                }
            } catch (Exception e) {
                // When the socket is closed by the client,
                // we throw our own SocketException
                // to break the "keep alive" loop above. If
                // the exception was anything other
                // than the expected SocketException OR a
                // SocketTimeoutException, print the
                // stacktrace
                if (!(e instanceof SocketException && "SridharFunctionalWebServer Shutdown".equals(e.getMessage())) && !(e instanceof SocketTimeoutException)) {
                    SridharFunctionalWebServer.LOG.log(Level.FINE, "Communication with the client broken", e);
                }
            } finally {
                safeClose(outputStream);
                safeClose(this.inputStream);
                safeClose(this.acceptSocket);
                this.asyncRunner.closed(this);
            }
        }
    }

    private static class Cookie {

        private final String n, v, e;

        @SuppressWarnings("unused")
		public Cookie(String name, String value, String expires) {
            this.n = name;
            this.v = value;
            this.e = expires;
        }

        public String getHTTPHeader() {
        	System.err.println("I doubt this ever gets called");
            String fmt = "%s=%s; expires=%s";
            return String.format(fmt, this.n, this.v, this.e);
        }
    }

    /**
     * Provides rudimentary support for cookies. Doesn't support 'path',
     * 'secure' nor 'httpOnly'. Feel free to improve it and/or add unsupported
     * features.
     * 
     * @author LordFokas
     */
    private static class CookieHandler implements Iterable<String> {

        private final HashMap<String, String> cookies = new HashMap<String, String>();

        private final ArrayList<Cookie> queue = new ArrayList<Cookie>();

        public CookieHandler(Map<String, String> httpHeaders) {
            String raw = httpHeaders.get("cookie");
            if (raw != null) {
                String[] tokens = raw.split(";");
                for (String token : tokens) {
                    String[] data = token.trim().split("=");
                    if (data.length == 2) {
                        this.cookies.put(data[0], data[1]);
                    }
                }
            }
        }

        @Override
        public Iterator<String> iterator() {
            return this.cookies.keySet().iterator();
        }

        /**
         * Internally used by the webserver to add all queued cookies into the
         * Response's HTTP Headers.
         * 
         * @param response
         *            The Response object to which headers the queued cookies
         *            will be added.
         */
        public void unloadQueue(Response response) {
            for (Cookie cookie : this.queue) {
                response.addHeader("Set-Cookie", cookie.getHTTPHeader());
            }
        }
    }

    /**
     * Default threading strategy for SridharFunctionalWebServer.
     * <p/>
     * <p>
     * By default, the server spawns a new Thread for every incoming request.
     * These are set to <i>daemon</i> status, and named according to the request
     * number. The name is useful when profiling the application.
     * </p>
     */
    private static class DefaultAsyncRunner implements AsyncRunner {

        private long requestCount;

        private final List<ClientHandler> running = Collections.synchronizedList(new ArrayList<SridharFunctionalWebServer.ClientHandler>());

        @Override
        public void closeAll() {
            // copy of the list for concurrency
            for (ClientHandler clientHandler : new ArrayList<ClientHandler>(this.running)) {
                clientHandler.close();
            }
        }

        @Override
        public void closed(ClientHandler clientHandler) {
            this.running.remove(clientHandler);
        }

        @Override
        public void exec(ClientHandler clientHandler) {
            ++this.requestCount;
            Thread t = new Thread(clientHandler);
            t.setDaemon(true);
            t.setName("SridharFunctionalWebServer Request Processor (#" + this.requestCount + ")");
            this.running.add(clientHandler);
            t.start();
        }
    }

    /**
     * Default strategy for creating and cleaning up temporary files.
     * <p/>
     * <p>
     * By default, files are created by <code>File.createTempFile()</code> in
     * the directory specified.
     * </p>
     */
    private static class DefaultTempFile implements TempFile {

        private final File file;

        private final OutputStream fstream;

        public DefaultTempFile(String tempdir) throws IOException {
            this.file = File.createTempFile("SridharFunctionalWebServer-", "", new File(tempdir));
            this.fstream = new FileOutputStream(this.file);
        }

        @Override
        public void delete() throws Exception {
            safeClose(this.fstream);
            if (!this.file.delete()) {
                throw new Exception("could not delete temporary file");
            }
        }

        @Override
        public String getName() {
            return this.file.getAbsolutePath();
        }

        @Override
        public OutputStream open() throws Exception {
            return this.fstream;
        }
    }

    /**
     * Default strategy for creating and cleaning up temporary files.
     * <p/>
     * <p>
     * This class stores its files in the standard location (that is, wherever
     * <code>java.io.tmpdir</code> points to). Files are added to an internal
     * list, and deleted when no longer needed (that is, when
     * <code>clear()</code> is invoked at the end of processing a request).
     * </p>
     */
    private static class DefaultTempFileManager implements TempFileManager {

        private final String tmpdir;

        private final List<TempFile> tempFiles;

        public DefaultTempFileManager() {
            this.tmpdir = System.getProperty("java.io.tmpdir");
            this.tempFiles = new ArrayList<TempFile>();
        }

        @Override
        public void clear() {
            for (TempFile file : this.tempFiles) {
                try {
                    file.delete();
                } catch (Exception ignored) {
                    SridharFunctionalWebServer.LOG.log(Level.WARNING, "could not delete file ", ignored);
                }
            }
            this.tempFiles.clear();
        }

        @Override
        public TempFile createTempFile() throws Exception {
            DefaultTempFile tempFile = new DefaultTempFile(this.tmpdir);
            this.tempFiles.add(tempFile);
            return tempFile;
        }
    }

    /**
     * Default strategy for creating and cleaning up temporary files.
     */
    private static class DefaultTempFileManagerFactory implements TempFileManagerFactory {

        @Override
        public TempFileManager create() {
            return new DefaultTempFileManager();
        }
    }

    private static class HTTPSession implements IHTTPSession {

        public static final int BUFSIZE = 8192;

        private final TempFileManager tempFileManager;

        private final OutputStream outputStream;

        private final PushbackInputStream inputStream;

        private int splitbyte;

        private int rlen;

        private String uri;

        private Method method;

        private Map<String, String> parms;

        private Map<String, String> headers;

        private CookieHandler cookies;

        private String queryParameterString;

        private String remoteIp;

        public HTTPSession(TempFileManager tempFileManager, InputStream inputStream, OutputStream outputStream, InetAddress inetAddress) {
            this.tempFileManager = tempFileManager;
            this.inputStream = new PushbackInputStream(inputStream, HTTPSession.BUFSIZE);
            this.outputStream = outputStream;
            this.remoteIp = inetAddress.isLoopbackAddress() || inetAddress.isAnyLocalAddress() ? HOST : inetAddress.getHostAddress().toString();
            this.headers = new HashMap<String, String>();
        }

        /**
         * Decodes the sent headers and loads the data into Key/value pairs
         */
        private void decodeHeader(BufferedReader inStream, Map<String, String> pre, Map<String, String> parms, Map<String, String> headers) throws ResponseException {
            try {
                // Read the request line
                String inLine = inStream.readLine();
                if (inLine == null) {
                    return;
                }

                StringTokenizer st = new StringTokenizer(inLine);
                if (!st.hasMoreTokens()) {
                    throw new ResponseException(Status.BAD_REQUEST, "BAD REQUEST: Syntax error. Usage: GET /example/file.html");
                }

                pre.put("method", st.nextToken());

                if (!st.hasMoreTokens()) {
                    throw new ResponseException(Status.BAD_REQUEST, "BAD REQUEST: Missing URI. Usage: GET /example/file.html");
                }

                String uri = st.nextToken();

                // Decode parameters from the URI
                int qmi = uri.indexOf('?');
                if (qmi >= 0) {
                    decodeParms(uri.substring(qmi + 1), parms);
                    uri = decodePercent(uri.substring(0, qmi));
                } else {
                    uri = decodePercent(uri);
                }

                // If there's another token, its protocol version,
                // followed by HTTP headers. Ignore version but parse headers.
                // NOTE: this now forces header names lower case since they are
                // case insensitive and vary by client.
                if (st.hasMoreTokens()) {
                    if (!st.nextToken().equals("HTTP/1.1")) {
                        throw new ResponseException(Status.UNSUPPORTED_HTTP_VERSION, "Only HTTP/1.1 is supported.");
                    }
                } else {
                    SridharFunctionalWebServer.LOG.log(Level.FINE, "no protocol version specified, strange..");
                }
                String line = inStream.readLine();
                while (line != null && line.trim().length() > 0) {
                    int p = line.indexOf(':');
                    if (p >= 0) {
                        headers.put(line.substring(0, p).trim().toLowerCase(Locale.US), line.substring(p + 1).trim());
                    }
                    line = inStream.readLine();
                }

                pre.put("uri", uri);
            } catch (IOException ioe) {
                throw new ResponseException(Status.INTERNAL_ERROR, "SERVER INTERNAL ERROR: IOException: " + ioe.getMessage(), ioe);
            }
        }

        /**
         * Decodes the Multipart Body data and put it into Key/Value pairs.
         */
        private void decodeMultipartData(String boundary, ByteBuffer fbuf, BufferedReader inStream, Map<String, String> parms, Map<String, String> files) throws ResponseException {
            try {
                int[] bpositions = getBoundaryPositions(fbuf, boundary.getBytes());
                int boundarycount = 1;
                String mpline = inStream.readLine();
                while (mpline != null) {
                    if (!mpline.contains(boundary)) {
                        throw new ResponseException(Status.BAD_REQUEST,
                                "BAD REQUEST: Content type is multipart/form-data but next chunk does not start with boundary. Usage: GET /example/file.html");
                    }
                    boundarycount++;
                    Map<String, String> item = new HashMap<String, String>();
                    mpline = inStream.readLine();
                    while (mpline != null && mpline.trim().length() > 0) {
                        int p = mpline.indexOf(':');
                        if (p != -1) {
                            item.put(mpline.substring(0, p).trim().toLowerCase(Locale.US), mpline.substring(p + 1).trim());
                        }
                        mpline = inStream.readLine();
                    }
                    if (mpline != null) {
                        String contentDisposition = item.get("content-disposition");
                        if (contentDisposition == null) {
                            throw new ResponseException(Status.BAD_REQUEST,
                                    "BAD REQUEST: Content type is multipart/form-data but no content-disposition info found. Usage: GET /example/file.html");
                        }
                        StringTokenizer st = new StringTokenizer(contentDisposition, ";");
                        Map<String, String> disposition = new HashMap<String, String>();
                        while (st.hasMoreTokens()) {
                            String token = st.nextToken().trim();
                            int p = token.indexOf('=');
                            if (p != -1) {
                                disposition.put(token.substring(0, p).trim().toLowerCase(Locale.US), token.substring(p + 1).trim());
                            }
                        }
                        String pname = disposition.get("name");
                        pname = pname.substring(1, pname.length() - 1);

                        String value = "";
                        if (item.get("content-type") == null) {
                            while (mpline != null && !mpline.contains(boundary)) {
                                mpline = inStream.readLine();
                                if (mpline != null) {
                                    int d = mpline.indexOf(boundary);
                                    if (d == -1) {
                                        value += mpline;
                                    } else {
                                        value += mpline.substring(0, d - 2);
                                    }
                                }
                            }
                        } else {
                            if (boundarycount > bpositions.length) {
                                throw new ResponseException(Status.INTERNAL_ERROR, "Error processing request");
                            }
                            int offset = stripMultipartHeaders(fbuf, bpositions[boundarycount - 2]);
                            String path = saveTmpFile(fbuf, offset, bpositions[boundarycount - 1] - offset - 4);
                            if (!files.containsKey(pname)) {
                                files.put(pname, path);
                            } else {
                                int count = 2;
                                while (files.containsKey(pname + count)) {
                                    count++;
                                }
                                files.put(pname + count, path);
                            }
                            value = disposition.get("filename");
                            value = value.substring(1, value.length() - 1);
                            mpline = inStream.readLine();
                            while (mpline != null && !mpline.contains(boundary)) {
                                mpline = inStream.readLine();
                            } 
                        }
                        parms.put(pname, value);
                    }
                }
            } catch (IOException ioe) {
                throw new ResponseException(Status.INTERNAL_ERROR, "SERVER INTERNAL ERROR: IOException: " + ioe.getMessage(), ioe);
            }
        }

        /**
         * Decodes parameters in percent-encoded URI-format ( e.g.
         * "name=Jack%20Daniels&pass=Single%20Malt" ) and adds them to given
         * Map. NOTE: this doesn't support multiple identical keys due to the
         * simplicity of Map.
         */
        private void decodeParms(String parms, Map<String, String> p) {
            if (parms == null) {
                this.queryParameterString = "";
                return;
            }

            this.queryParameterString = parms;
            StringTokenizer st = new StringTokenizer(parms, "&");
            while (st.hasMoreTokens()) {
                String e = st.nextToken();
                int sep = e.indexOf('=');
                if (sep >= 0) {
                    p.put(decodePercent(e.substring(0, sep)).trim(), decodePercent(e.substring(sep + 1)));
                } else {
                    p.put(decodePercent(e).trim(), "");
                }
            }
        }

        @Override
        public void execute(Map<String, WebServerPlugin> mimeTypeHandlers, boolean quiet, List<File> rootDirs) throws IOException {
            try {
                // Read the first 8192 bytes.
                // The full header should fit in here.
                // Apache's default header limit is 8KB.
                // Do NOT assume that a single read will get the entire header
                // at once!
                byte[] buf = new byte[HTTPSession.BUFSIZE];
                this.splitbyte = 0;
                this.rlen = 0;

                int read = -1;
                try {
                    read = this.inputStream.read(buf, 0, HTTPSession.BUFSIZE);
                } catch (Exception e) {
                    safeClose(this.inputStream);
                    safeClose(this.outputStream);
                    throw new SocketException("SridharFunctionalWebServer Shutdown");
                }
                if (read == -1) {
                    // socket was been closed
                    safeClose(this.inputStream);
                    safeClose(this.outputStream);
                    throw new SocketException("SridharFunctionalWebServer Shutdown");
                }
                while (read > 0) {
                    this.rlen += read;
                    this.splitbyte = findHeaderEnd(buf, this.rlen);
                    if (this.splitbyte > 0) {
                        break;
                    }
                    read = this.inputStream.read(buf, this.rlen, HTTPSession.BUFSIZE - this.rlen);
                }

                if (this.splitbyte < this.rlen) {
                    this.inputStream.unread(buf, this.splitbyte, this.rlen - this.splitbyte);
                }

                this.parms = new HashMap<String, String>();
                if (null == this.headers) {
                    this.headers = new HashMap<String, String>();
                } else {
                    this.headers.clear();
                }

                if (null != this.remoteIp) {
                    this.headers.put("remote-addr", this.remoteIp);
                    this.headers.put("http-client-ip", this.remoteIp);
                }

                // Create a BufferedReader for parsing the header.
                BufferedReader hin = new BufferedReader(new InputStreamReader(new ByteArrayInputStream(buf, 0, this.rlen)));

                // Decode the header into parms and header java properties
                Map<String, String> pre = new HashMap<String, String>();
                decodeHeader(hin, pre, this.parms, this.headers);

                this.method = Method.lookup(pre.get("method"));
                if (this.method == null) {
                    throw new ResponseException(Status.BAD_REQUEST, "BAD REQUEST: Syntax error.");
                }

                this.uri = pre.get("uri");

                this.cookies = new CookieHandler(this.headers);

                // Ok, now do the serve()
                Response r = SridharFunctionalWebServer.serve(this, mimeTypeHandlers, MIME_TYPES, quiet, rootDirs);
                if (r == null) {
                    throw new ResponseException(Status.INTERNAL_ERROR, "SERVER INTERNAL ERROR: Serve() returned a null response.");
                } else {
                    this.cookies.unloadQueue(r);
                    r.setRequestMethod(this.method);
                    r.send(this.outputStream);
                }
            } catch (SocketException e) {
                // throw it out to close socket object (finalAccept)
                throw e;
            } catch (SocketTimeoutException ste) {
                // treat socket timeouts the same way we treat socket exceptions
                // i.e. close the stream & finalAccept object by throwing the
                // exception up the call stack.
                throw ste;
            } catch (IOException ioe) {
                Response r = newFixedLengthResponse(Status.INTERNAL_ERROR, SridharFunctionalWebServer.MIME_PLAINTEXT, "SERVER INTERNAL ERROR: IOException: " + ioe.getMessage());
                r.send(this.outputStream);
                safeClose(this.outputStream);
            } catch (ResponseException re) {
                Response r = newFixedLengthResponse(re.getStatus(), SridharFunctionalWebServer.MIME_PLAINTEXT, re.getMessage());
                r.send(this.outputStream);
                safeClose(this.outputStream);
            } finally {
                this.tempFileManager.clear();
            }
        }

        /**
         * Find byte index separating header from body. It must be the last byte
         * of the first two sequential new lines.
         */
        private int findHeaderEnd(final byte[] buf, int rlen) {
            int splitbyte = 0;
            while (splitbyte + 3 < rlen) {
                if (buf[splitbyte] == '\r' && buf[splitbyte + 1] == '\n' && buf[splitbyte + 2] == '\r' && buf[splitbyte + 3] == '\n') {
                    return splitbyte + 4;
                }
                splitbyte++;
            }
            return 0;
        }

        /**
         * Find the byte positions where multipart boundaries start.
         */
        private int[] getBoundaryPositions(ByteBuffer b, byte[] boundary) {
            int matchcount = 0;
            int matchbyte = -1;
            List<Integer> matchbytes = new ArrayList<Integer>();
            for (int i = 0; i < b.limit(); i++) {
                if (b.get(i) == boundary[matchcount]) {
                    if (matchcount == 0) {
                        matchbyte = i;
                    }
                    matchcount++;
                    if (matchcount == boundary.length) {
                        matchbytes.add(matchbyte);
                        matchcount = 0;
                        matchbyte = -1;
                    }
                } else {
                    i -= matchcount;
                    matchcount = 0;
                    matchbyte = -1;
                }
            }
            int[] ret = new int[matchbytes.size()];
            for (int i = 0; i < ret.length; i++) {
                ret[i] = matchbytes.get(i);
            }
            return ret;
        }

        @Override
        public CookieHandler getCookies() {
            return this.cookies;
        }

        @Override
        public final Map<String, String> getHeaders() {
            return this.headers;
        }

        @Override
        public final InputStream getInputStream() {
            return this.inputStream;
        }

        @Override
        public final Method getMethod() {
            return this.method;
        }

        @Override
        public final Map<String, String> getParms() {
            return this.parms;
        }

        @Override
        public String getQueryParameterString() {
            return this.queryParameterString;
        }

        private RandomAccessFile getTmpBucket() {
            try {
                TempFile tempFile = this.tempFileManager.createTempFile();
                return new RandomAccessFile(tempFile.getName(), "rw");
            } catch (Exception e) {
                throw new Error(e); // we won't recover, so throw an error
            }
        }

        @Override
        public final String getUri() {
            return this.uri;
        }

        @Override
        public void parseBody(Map<String, String> files) throws IOException, ResponseException {
            RandomAccessFile randomAccessFile = null;
            BufferedReader inStream = null;
            try {

                randomAccessFile = getTmpBucket();

                long size;
                if (this.headers.containsKey("content-length")) {
                    size = Integer.parseInt(this.headers.get("content-length"));
                } else if (this.splitbyte < this.rlen) {
                    size = this.rlen - this.splitbyte;
                } else {
                    size = 0;
                }

                // Now read all the body and write it to f
                byte[] buf = new byte[512];
                while (this.rlen >= 0 && size > 0) {
                    this.rlen = this.inputStream.read(buf, 0, (int) Math.min(size, 512));
                    size -= this.rlen;
                    if (this.rlen > 0) {
                        randomAccessFile.write(buf, 0, this.rlen);
                    }
                }

                // Get the raw body as a byte []
                ByteBuffer fbuf = randomAccessFile.getChannel().map(FileChannel.MapMode.READ_ONLY, 0, randomAccessFile.length());
                randomAccessFile.seek(0);

                // Create a BufferedReader for easily reading it as string.
                InputStream bin = new FileInputStream(randomAccessFile.getFD());
                inStream = new BufferedReader(new InputStreamReader(bin));

                // If the method is POST, there may be parameters
                // in data section, too, read it:
                if (Method.POST.equals(this.method)) {
                    String contentType = "";
                    String contentTypeHeader = this.headers.get("content-type");

                    StringTokenizer st = null;
                    if (contentTypeHeader != null) {
                        st = new StringTokenizer(contentTypeHeader, ",; ");
                        if (st.hasMoreTokens()) {
                            contentType = st.nextToken();
                        }
                    }

                    if ("multipart/form-data".equalsIgnoreCase(contentType)) {
                        // Handle multipart/form-data
                        if (!st.hasMoreTokens()) {
                            throw new ResponseException(Status.BAD_REQUEST,
                                    "BAD REQUEST: Content type is multipart/form-data but boundary missing. Usage: GET /example/file.html");
                        }

                        String boundaryStartString = "boundary=";
                        int boundaryContentStart = contentTypeHeader.indexOf(boundaryStartString) + boundaryStartString.length();
                        String boundary = contentTypeHeader.substring(boundaryContentStart, contentTypeHeader.length());
                        if (boundary.startsWith("\"") && boundary.endsWith("\"")) {
                            boundary = boundary.substring(1, boundary.length() - 1);
                        }

                        decodeMultipartData(boundary, fbuf, inStream, this.parms, files);
                    } else {
                        String postLine = "";
                        StringBuilder postLineBuffer = new StringBuilder();
                        char[] pbuf = new char[512];
                        int read = inStream.read(pbuf);
                        while (read >= 0) {
                            postLine = String.valueOf(pbuf, 0, read);
                            postLineBuffer.append(postLine);
                            read = inStream.read(pbuf);
                        }
                        postLine = postLineBuffer.toString().trim();
                        // Handle application/x-www-form-urlencoded
                        if ("application/x-www-form-urlencoded".equalsIgnoreCase(contentType)) {
                            decodeParms(postLine, this.parms);
                        } else if (postLine.length() != 0) {
                            // Special case for raw POST data => create a
                            // special files entry "postData" with raw content
                            // data
                            files.put("postData", postLine);
                        }
                    }
                } else if (Method.PUT.equals(this.method)) {
                    files.put("content", saveTmpFile(fbuf, 0, fbuf.limit()));
                }
            } finally {
                safeClose(randomAccessFile);
                safeClose(inStream);
            }
        }

        /**
         * Retrieves the content of a sent file and saves it to a temporary
         * file. The full path to the saved file is returned.
         */
        private String saveTmpFile(ByteBuffer b, int offset, int len) {
            String path = "";
            if (len > 0) {
                FileOutputStream fileOutputStream = null;
                try {
                    TempFile tempFile = this.tempFileManager.createTempFile();
                    ByteBuffer src = b.duplicate();
                    fileOutputStream = new FileOutputStream(tempFile.getName());
                    FileChannel dest = fileOutputStream.getChannel();
                    src.position(offset).limit(offset + len);
                    dest.write(src.slice());
                    path = tempFile.getName();
                } catch (Exception e) { // Catch exception if any
                    throw new Error(e); // we won't recover, so throw an error
                } finally {
                    safeClose(fileOutputStream);
                }
            }
            return path;
        }

        /**
         * It returns the offset separating multipart file headers from the
         * file's data.
         */
        private int stripMultipartHeaders(ByteBuffer b, int offset) {
            int i;
            for (i = offset; i < b.limit(); i++) {
                if (b.get(i) == '\r' && b.get(++i) == '\n' && b.get(++i) == '\r' && b.get(++i) == '\n') {
                    break;
                }
            }
            return i + 1;
        }
    }

    /**
     * Handles one session, i.e. parses the HTTP request and returns the
     * response.
     */
    private static interface IHTTPSession {

		void execute(Map<String, WebServerPlugin> mimeTypeHandlers,
				boolean quiet, List<File> rootDirs) throws IOException;

		CookieHandler getCookies();

        Map<String, String> getHeaders();

        InputStream getInputStream();

        Method getMethod();

        Map<String, String> getParms();

        String getQueryParameterString();

        /**
         * @return the path part of the URL.
         */
        String getUri();

        /**
         * Adds the files in the request body to the files map.
         * 
         * @param files
         *            map to modify
         */
        void parseBody(Map<String, String> files) throws IOException, ResponseException;
    }

    /**
     * HTTP Request methods, with the ability to decode a <code>String</code>
     * back to its enum value.
     */
    private static enum Method {
        GET,
        PUT,
        POST,
        DELETE,
        HEAD,
        OPTIONS;

        static Method lookup(String method) {
            for (Method m : Method.values()) {
                if (m.toString().equalsIgnoreCase(method)) {
                    return m;
                }
            }
            return null;
        }
    }
 
    private interface IStatus {

        String getDescription();

        int getRequestStatus();
    }

    /** Some HTTP response status codes */
    public enum Status implements IStatus {
        SWITCH_PROTOCOL(101, "Switching Protocols"),
        OK(200, "OK"),
        CREATED(201, "Created"),
        ACCEPTED(202, "Accepted"),
        NO_CONTENT(204, "No Content"),
        PARTIAL_CONTENT(206, "Partial Content"),
        REDIRECT(301, "Moved Permanently"),
        NOT_MODIFIED(304, "Not Modified"),
        BAD_REQUEST(400, "Bad Request"),
        UNAUTHORIZED(401, "Unauthorized"),
        FORBIDDEN(403, "Forbidden"),
        NOT_FOUND(404, "Not Found"),
        METHOD_NOT_ALLOWED(405, "Method Not Allowed"),
        RANGE_NOT_SATISFIABLE(416, "Requested Range Not Satisfiable"),
        INTERNAL_ERROR(500, "Internal Server Error"),
        UNSUPPORTED_HTTP_VERSION(505, "HTTP Version Not Supported");

        private final int requestStatus;

        private final String description;

        Status(int requestStatus, String description) {
            this.requestStatus = requestStatus;
            this.description = description;
        }

        @Override
        public String getDescription() {
            return "" + this.requestStatus + " " + this.description;
        }

        @Override
        public int getRequestStatus() {
            return this.requestStatus;
        }
    }
	
    /** HTTP response. Return one of these from serve(). */
    private static class Response {

        /** HTTP status code after processing, e.g. "200 OK", Status.OK */
        private IStatus status;

        /** MIME type of content, e.g. "text/html" */
        private String mimeType;

        /** Data of the response, may be null. */
        private InputStream data;

        private long contentLength;

        /**
         * Headers for the HTTP response. Use addHeader() to add lines.
         */
        private final Map<String, String> header = new HashMap<String, String>();

        /**
         * The request method that spawned this response.
         */
        private Method requestMethod;

        /**
         * Use chunkedTransfer
         */
        private boolean chunkedTransfer;

        /**
         * Creates a fixed length response if totalBytes>=0, otherwise chunked.
         */
        private Response(IStatus status, String mimeType, InputStream data, long totalBytes) {
            this.status = status;
            this.mimeType = mimeType;
            if (data == null) {
                this.data = new ByteArrayInputStream(new byte[0]);
                this.contentLength = 0L;
            } else {
                this.data = data;
                this.contentLength = totalBytes;
            }
            this.chunkedTransfer = this.contentLength < 0;
        }

        /**
         * Adds given line to the header.
         */
        public void addHeader(String name, String value) {
            this.header.put(name, value);
        }

        private boolean headerAlreadySent(Map<String, String> header, String name) {
            boolean alreadySent = false;
            for (String headerName : header.keySet()) {
                alreadySent |= headerName.equalsIgnoreCase(name);
            }
            return alreadySent;
        }

        /**
         * Sends given response to the socket.
         */
        private void send(OutputStream outputStream) {
            String mime = this.mimeType;
            SimpleDateFormat gmtFrmt = new SimpleDateFormat("E, d MMM yyyy HH:mm:ss 'GMT'", Locale.US);
            gmtFrmt.setTimeZone(TimeZone.getTimeZone("GMT"));

            try {
                if (this.status == null) {
                    throw new Error("sendResponse(): Status can't be null.");
                }
                PrintWriter pw = new PrintWriter(new BufferedWriter(new OutputStreamWriter(outputStream, "UTF-8")), false);
                pw.print("HTTP/1.1 " + this.status.getDescription() + " \r\n");

                if (mime != null) {
                    pw.print("Content-Type: " + mime + "\r\n");
                }

                if (this.header == null || this.header.get("Date") == null) {
                    pw.print("Date: " + gmtFrmt.format(new Date()) + "\r\n");
                }

                if (this.header != null) {
                    for (String key : this.header.keySet()) {
                        String value = this.header.get(key);
                        pw.print(key + ": " + value + "\r\n");
                    }
                }

                sendConnectionHeaderIfNotAlreadyPresent(pw, this.header);

                if (this.requestMethod != Method.HEAD && this.chunkedTransfer) {
                    sendAsChunked(outputStream, pw);
                } else {
                    long pending = this.data != null ? this.contentLength : 0;
                    pending = sendContentLengthHeaderIfNotAlreadyPresent(pw, this.header, pending);
                    pw.print("\r\n");
                    pw.flush();
                    sendAsFixedLength(outputStream, pending);
                }
                outputStream.flush();
                safeClose(this.data);
            } catch (IOException ioe) {
                SridharFunctionalWebServer.LOG.log(Level.SEVERE, "Could not send response to the client", ioe);
            }
        }

        private void sendAsChunked(OutputStream outputStream, PrintWriter pw) throws IOException {
            pw.print("Transfer-Encoding: chunked\r\n");
            pw.print("\r\n");
            pw.flush();
            int BUFFER_SIZE = 16 * 1024;
            byte[] CRLF = "\r\n".getBytes();
            byte[] buff = new byte[BUFFER_SIZE];
            int read;
            while ((read = this.data.read(buff)) > 0) {
                outputStream.write(String.format("%x\r\n", read).getBytes());
                outputStream.write(buff, 0, read);
                outputStream.write(CRLF);
            }
            outputStream.write(String.format("0\r\n\r\n").getBytes());
        }

        private void sendAsFixedLength(OutputStream outputStream, long pending) throws IOException {
            if (this.requestMethod != Method.HEAD && this.data != null) {
                long BUFFER_SIZE = 16 * 1024;
                byte[] buff = new byte[(int) BUFFER_SIZE];
                while (pending > 0) {
                    int read = this.data.read(buff, 0, (int) (pending > BUFFER_SIZE ? BUFFER_SIZE : pending));
                    if (read <= 0) {
                        break;
                    }
                    outputStream.write(buff, 0, read);
                    pending -= read;
                }
            }
        }

        private void sendConnectionHeaderIfNotAlreadyPresent(PrintWriter pw, Map<String, String> header) {
            if (!headerAlreadySent(header, "connection")) {
                pw.print("Connection: keep-alive\r\n");
            }
        }

        private long sendContentLengthHeaderIfNotAlreadyPresent(PrintWriter pw, Map<String, String> header, long size) {
            for (String headerName : header.keySet()) {
                if (headerName.equalsIgnoreCase("content-length")) {
                    try {
                        return Long.parseLong(header.get(headerName));
                    } catch (NumberFormatException ex) {
                        return size;
                    }
                }
            }

            pw.print("Content-Length: " + size + "\r\n");
            return size;
        }

        public void setRequestMethod(Method requestMethod) {
            this.requestMethod = requestMethod;
        }
    }

    private static final class ResponseException extends Exception {

        private static final long serialVersionUID = 6569838532917408380L;

        private final Status status;

        public ResponseException(Status status, String message) {
            super(message);
            this.status = status;
        }

        public ResponseException(Status status, String message, Exception e) {
            super(message, e);
            this.status = status;
        }

        public Status getStatus() {
            return this.status;
        }
    }

    /**
     * The runnable that will be used for the main listening thread.
     */
    private static class ServerRunnable implements Runnable {

        private final int timeout;
        private final AsyncRunner asyncRunner;
        private final ServerSocket myServerSocket;
        private final TempFileManagerFactory tempFileManagerFactory;
        private final Map<String, WebServerPlugin> mimeTypeHandlers;
        private final boolean quiet;
        private final List<File> rootDirs;
        
		private ServerRunnable(int timeout, AsyncRunner asyncRunner,
				ServerSocket myServerSocket, TempFileManagerFactory tempFileManagerFactory,
				Map<String, WebServerPlugin> mimeTypeHandlers,
				boolean quiet, List<File> rootDirs) {
            this.timeout = timeout;
            this.asyncRunner = asyncRunner;
            this.myServerSocket = Preconditions.checkNotNull(myServerSocket);
            this.tempFileManagerFactory = tempFileManagerFactory;
            this.mimeTypeHandlers = mimeTypeHandlers;
            this.quiet = quiet;
            this.rootDirs = rootDirs;
        }

        @Override
        public void run() {
        	boolean begin = true;
        	 while (begin || !myServerSocket.isClosed()) {
            	if (begin) {
            		begin = false;
            	}
                try {
                    final Socket finalAccept = myServerSocket.accept();
                    if (this.timeout > 0) {
                        finalAccept.setSoTimeout(this.timeout);
                    }
                    final InputStream inputStream = finalAccept.getInputStream();
                    this.asyncRunner.exec(SridharFunctionalWebServer.createClientHandler(finalAccept, inputStream, tempFileManagerFactory,asyncRunner,mimeTypeHandlers, quiet, rootDirs));
                } catch (IOException e) {
                    SridharFunctionalWebServer.LOG.log(Level.FINE, "Communication with the client broken", e);
                }
            }
        }
    }

    /**
     * A temp file.
     * <p/>
     * <p>
     * Temp files are responsible for managing the actual temporary storage and
     * cleaning themselves up when no longer needed.
     * </p>
     */
    private static interface TempFile {

        void delete() throws Exception;

        String getName();

        OutputStream open() throws Exception;
    }

    /**
     * Temp file manager.
     * <p/>
     * <p>
     * Temp file managers are created 1-to-1 with incoming requests, to create
     * and cleanup temporary files created as a result of handling the request.
     * </p>
     */
    private static interface TempFileManager {

        void clear();

        TempFile createTempFile() throws Exception;
    }

    /**
     * Factory to create temp file managers.
     */
    private static interface TempFileManagerFactory {

        TempFileManager create();
    }

    /**
     * Maximum time to wait on Socket.getInputStream().read() (in milliseconds)
     * This is required as the Keep-Alive HTTP connections would otherwise block
     * the socket reading thread forever (or as long the browser is open).
     */
    public static final int SOCKET_READ_TIMEOUT = 5000;

    /**
     * Common MIME type for dynamic content: plain text
     */
    public static final String MIME_PLAINTEXT = "text/plain";

    /**
     * Common MIME type for dynamic content: html
     */
    public static final String MIME_HTML = "text/html";

    /**
     * logger to log to.
     */
    private static final Logger LOG = Logger.getLogger(SridharFunctionalWebServer.class.getName());

    private static final void safeClose(Closeable closeable) {
        if (closeable != null) {
            try {
                closeable.close();
            } catch (IOException e) {
                SridharFunctionalWebServer.LOG.log(Level.SEVERE, "Could not close", e);
            }
        }
    }

    // -------------------------------------------------------------------------------
    //
    // Threading Strategy.
    //
    // -------------------------------------------------------------------------------
    
    /**
     * create a instance of the client handler, subclasses can return a subclass
     * of the ClientHandler.
     * 
     * @param finalAccept
     *            the socket the cleint is connected to
     * @param inputStream
     *            the input stream
     * @return the client handler
     */
    private static ClientHandler createClientHandler(final Socket finalAccept, final InputStream inputStream, final TempFileManagerFactory tempFileManagerFactory, AsyncRunner asyncRunner, Map<String, WebServerPlugin> mimeTypeHandlers, boolean quiet, List<File> rootDirs) {
        return new ClientHandler(inputStream, finalAccept, tempFileManagerFactory, asyncRunner, mimeTypeHandlers, quiet, rootDirs);
    }

    /**
     * Instantiate the server runnable, can be overwritten by subclasses to
     * provide a subclass of the ServerRunnable.
     * 
     * @param timeout
     *            the socet timeout to use.
     * @param asyncRunner2 
     * @return the server runnable.
     */
    private static ServerRunnable createServerRunnable(final int timeout,ServerSocket myServerSocket,TempFileManagerFactory tempFileManagerFactory,Map<String, WebServerPlugin> mimeTypeHandlers, AsyncRunner asyncRunner2, boolean quiet, List<File> rootDirs, AsyncRunner asyncRunner) {
        return new ServerRunnable(timeout,asyncRunner,myServerSocket, tempFileManagerFactory,mimeTypeHandlers, quiet, rootDirs);
    }


    // -------------------------------------------------------------------------------
    
    /**
     * Decode percent encoded <code>String</code> values.
     * 
     * @param str
     *            the percent encoded <code>String</code>
     * @return expanded form of the input, for example "foo%20bar" becomes
     *         "foo bar"
     */
    private static String decodePercent(String str) {
        String decoded = null;
        try {
            decoded = URLDecoder.decode(str, "UTF8");
        } catch (UnsupportedEncodingException ignored) {
            SridharFunctionalWebServer.LOG.log(Level.WARNING, "Encoding not supported, ignored", ignored);
        }
        return decoded;
    }

    /**
     * Create a response with known length.
     */
    private static Response newFixedLengthResponse(IStatus status, String mimeType, InputStream data, long totalBytes) {
        return new Response(status, mimeType, data, totalBytes);
    }

    /**
     * Create a text response with known length.
     */
    private static Response superNewFixedLengthResponse(IStatus status, String mimeType, String txt) {
        if (txt == null) {
            return newFixedLengthResponse(status, mimeType, new ByteArrayInputStream(new byte[0]), 0);
        } else {
            byte[] bytes;
            try {
                bytes = txt.getBytes("UTF-8");
            } catch (UnsupportedEncodingException e) {
                SridharFunctionalWebServer.LOG.log(Level.SEVERE, "encoding problem, responding nothing", e);
                bytes = new byte[0];
            }
            return newFixedLengthResponse(status, mimeType, new ByteArrayInputStream(bytes), bytes.length);
        }
    }

	private static Thread createThread(Map<String, WebServerPlugin> mimeTypeHandlers,
			int timeout, TempFileManagerFactory tempFileManagerFactory,
			AsyncRunner asyncRunner, ServerSocket ss, boolean quiet, List<File> rootDirs) {
		Thread aThread = new Thread(createServerRunnable(timeout, ss, tempFileManagerFactory,
				mimeTypeHandlers, asyncRunner, quiet, rootDirs, asyncRunner));
		aThread.setDaemon(true);
		aThread.setName("SridharFunctionalWebServer Main Listener");
		aThread.start();
		return aThread;
	}

	private static InetSocketAddress getEndpoint(String hostname, int myPort) {
		InetSocketAddress endpoint = hostname != null ? new InetSocketAddress(hostname, myPort) : new InetSocketAddress(myPort);
		return endpoint;
	}

	private static ServerSocket createServerSocket(SSLServerSocketFactory sslServerSocketFactory, String hostname2, int myPort2) throws IOException {
		ServerSocket myServerSocket; 
		if (sslServerSocketFactory != null) {
            SSLServerSocket ss = (SSLServerSocket) sslServerSocketFactory.createServerSocket();
            ss.setNeedClientAuth(false);
            myServerSocket = ss;
        } else {
            myServerSocket = new ServerSocket();
        }
		myServerSocket.setReuseAddress(true);
        myServerSocket.bind(getEndpoint(hostname2, myPort2));
		return myServerSocket;
	}

    /**
     * Stop the server.
     * @param ss 
     */
	private static void stop(ServerSocket myServerSocket, Thread myThread,
			AsyncRunner asyncRunner) {
        try {
            safeClose(myServerSocket);
            asyncRunner.closeAll();
            if (myThread != null) {
                myThread.join();
            }
        } catch (Exception e) {
            SridharFunctionalWebServer.LOG.log(Level.SEVERE, "Could not stop all connections", e);
        }
    }

    /**
     * Common mime type for dynamic content: binary
     */
    public static final String MIME_DEFAULT_BINARY = "application/octet-stream";

    /**
     * Default Index file names.
     */
    @SuppressWarnings("serial")
    public static final List<String> INDEX_FILE_NAMES = new ArrayList<String>() {

        {
            add("index.html");
            add("index.htm");
        }
    };

    /**
     * Hashtable mapping (String)FILENAME_EXTENSION -> (String)MIME_TYPE
     */
    @SuppressWarnings("serial")
    private static final Map<String, String> MIME_TYPES = new HashMap<String, String>() {

        {
            put("css", "text/css");
            put("htm", "text/html");
            put("html", "text/html");
            put("xml", "text/xml");
            put("java", "text/x-java-source, text/java");
            put("md", "text/plain");
            put("txt", "text/plain");
            put("asc", "text/plain");
            put("gif", "image/gif");
            put("jpg", "image/jpeg");
            put("jpeg", "image/jpeg");
            put("png", "image/png");
            put("mp3", "audio/mpeg");
            put("m3u", "audio/mpeg-url");
            put("mp4", "video/mp4");
            put("ogv", "video/ogg");
            put("flv", "video/x-flv");
            put("mov", "video/quicktime");
            put("swf", "application/x-shockwave-flash");
            put("js", "application/javascript");
            put("pdf", "application/pdf");
            put("doc", "application/msword");
            put("ogg", "application/x-ogg");
            put("zip", "application/octet-stream");
            put("exe", "application/octet-stream");
            put("class", "application/octet-stream");
        }
    };

	private static ImmutableMap<String, WebServerPlugin> createPluginsMap(
			String[] args, String host, int port, boolean quiet,
			ImmutableList<File> rootDirs) {
		return createPluginsMap(quiet, 
				getOptions(host, 
						port, 
						quiet, 
						rootDirs, 
						getOptionsBuilder(args)));
	}

	private static ImmutableMap<String, WebServerPlugin> createPluginsMap(
			final boolean quiet, ImmutableMap<String, String> options) {
		ImmutableMap.Builder<String, WebServerPlugin> mimeTypeHandlersBuilder = ImmutableMap.builder();
        for (WebServerPluginInfo info : ServiceLoader.load(WebServerPluginInfo.class)) {
            String[] mimeTypes = info.getMimeTypes();
            for (String mime : mimeTypes) {
                String[] indexFiles = info.getIndexFilesForMimeType(mime);
                if (!quiet) {
                    System.out.print("# Found plugin for Mime type: \"" + mime + "\"");
                    if (indexFiles != null) {
                        System.out.print(" (serving index files: ");
                        for (String indexFile : indexFiles) {
                            System.out.print(indexFile + " ");
                        }
                    }
                    System.out.println(").");
                }
				registerPluginForMimeType(indexFiles, mime,
						info.getWebServerPlugin(mime), options,
						mimeTypeHandlersBuilder);
            }
        }

        ImmutableMap<String, WebServerPlugin> build = mimeTypeHandlersBuilder.build();
		return build;
	}

	private static ImmutableList<File> getRootDirs(String[] args) {
		List<File> rootDirs = getRootDirectories(args);
        if (rootDirs.isEmpty()) {
            rootDirs.add(new File(".").getAbsoluteFile());
        }
		return ImmutableList.copyOf(rootDirs);
	}

	private static ImmutableMap<String, String> getOptions(final String host,
			final int port, final boolean quiet, ImmutableList<File> rootDirs,
			ImmutableMap.Builder<String, String> optionsBuilder) {
		optionsBuilder.put("host", host);
        optionsBuilder.put("port", "" + port);
        optionsBuilder.put("quiet", String.valueOf(quiet));
        optionsBuilder.put("home", serializeFileList(rootDirs));
        
        ImmutableMap<String, String> options = optionsBuilder.build();
		return options;
	}

	private static ImmutableMap.Builder<String, String> getOptionsBuilder(
			String[] args) {
		// Parse command-line, with short and long versions of the options.
        ImmutableMap.Builder<String, String> optionsBuilder = ImmutableMap.builder();
        for (int i = 0; i < args.length; ++i) {
            String currentArg = args[i];
			if (currentArg.startsWith("-X:")) {
                int dot = currentArg.indexOf('=');
                if (dot > 0) {
                    String name = currentArg.substring(0, dot);
                    String value = currentArg.substring(dot + 1, currentArg.length());
                    optionsBuilder.put(name, value);
                }
            }
        }
		return optionsBuilder;
	}

	private static List<File> getRootDirectories(String[] args) {
		List<File> rootDirs = new ArrayList<File>();
        for (int i = 0; i < args.length; ++i) {
            String currentArg = args[i];
			if (currentArg.equalsIgnoreCase("-d") || currentArg.equalsIgnoreCase("--dir")) {
                rootDirs.add(new File(args[i + 1]).getAbsoluteFile());
            }
        }
		return rootDirs;
	}

	private static boolean isQuietMode(String[] args) {
		boolean quiet = false;
        for (int i = 0; i < args.length; ++i) {
            String currentArg = args[i];
			if (currentArg.equalsIgnoreCase("-q") || currentArg.equalsIgnoreCase("--quiet")) {
                quiet = true;
            }
        }
		return quiet;
	}

	private static int getPort(String[] args) {
		int port = PORT_NUMBER;
        for (int i = 0; i < args.length; ++i) {
            String currentArg = args[i];
			if (currentArg.equalsIgnoreCase("-p") || currentArg.equalsIgnoreCase("--port")) {
                port = Integer.parseInt(args[i + 1]);
            }
        }
		return port;
	}

	private static String getHost(String[] args) {
		String host = HOST;
        for (int i = 0; i < args.length; ++i) {
            String currentArg = args[i];
			if (currentArg.equalsIgnoreCase("-h") || currentArg.equalsIgnoreCase("--host")) {
                host = args[i + 1];
            } 
        }
		return host;
	}

	private static String serializeFileList(List<File> rootDirs) {
		StringBuilder sb = new StringBuilder();
        for (File dir : rootDirs) {
            if (sb.length() > 0) {
                sb.append(":");
            }
            try {
                sb.append(dir.getCanonicalPath());
            } catch (IOException ignored) {
            }
        }
        String string = sb.toString();
		return string;
	}

	private static void registerPluginForMimeType(String[] indexFiles,
			String mimeType, WebServerPlugin plugin,
			ImmutableMap<String, String> commandLineOptions,
			ImmutableMap.Builder<String, WebServerPlugin> mimeTypeHandlers) {
        if (mimeType == null || plugin == null) {
            return;
        }

        if (indexFiles != null) {
            for (String filename : indexFiles) {
                int dot = filename.lastIndexOf('.');
                if (dot >= 0) {
                    String extension = filename.substring(dot + 1).toLowerCase();
                    SridharFunctionalWebServer.MIME_TYPES.put(extension, mimeType);
                }
            }
            SridharFunctionalWebServer.INDEX_FILE_NAMES.addAll(Arrays.asList(indexFiles));
        }
        mimeTypeHandlers.put(mimeType, plugin);
        plugin.initialize(commandLineOptions);
    }

    /**
     * URL-encodes everything between "/"-characters. Encodes spaces as '%20'
     * instead of '+'.
     */
    private static String encodeUri(String uri) {
        String newUri = "";
        StringTokenizer st = new StringTokenizer(uri, "/ ", true);
        while (st.hasMoreTokens()) {
            String tok = st.nextToken();
            if (tok.equals("/")) {
                newUri += "/";
            } else if (tok.equals(" ")) {
                newUri += "%20";
            } else {
                try {
                    newUri += URLEncoder.encode(tok, "UTF-8");
                } catch (UnsupportedEncodingException ignored) {
                }
            }
        }
        return newUri;
    }

    private static String findIndexFileInDirectory(File directory) {
        for (String fileName : SridharFunctionalWebServer.INDEX_FILE_NAMES) {
            File indexFile = new File(directory, fileName);
            if (indexFile.isFile()) {
                return fileName;
            }
        }
        return null;
    }

    private static Response getForbiddenResponse(String s) {
        return newFixedLengthResponse(Status.FORBIDDEN, SridharFunctionalWebServer.MIME_PLAINTEXT, "FORBIDDEN: " + s);
    }

    private static Response getInternalErrorResponse(String s) {
        return newFixedLengthResponse(Status.INTERNAL_ERROR, SridharFunctionalWebServer.MIME_PLAINTEXT, "INTERNAL ERROR: " + s);
    }

    // Get MIME type from file name extension, if possible
    private static String getMimeTypeForFile(String uri, Map<String, String> MIME_TYPES) {
        int dot = uri.lastIndexOf('.');
        String mime = null;
        if (dot >= 0) {
            mime = MIME_TYPES.get(uri.substring(dot + 1).toLowerCase());
        }
        return mime == null ? SridharFunctionalWebServer.MIME_DEFAULT_BINARY : mime;
    }

    private static Response getNotFoundResponse() {
        return newFixedLengthResponse(Status.NOT_FOUND, SridharFunctionalWebServer.MIME_PLAINTEXT, "Error 404, file not found.");
    }

    private static String listDirectory(String uri, File f) {
        String heading = "Directory " + uri;
        StringBuilder msg =
                new StringBuilder("<html><head><title>" + heading + "</title><style><!--\n" + "span.dirname { font-weight: bold; }\n" + "span.filesize { font-size: 75%; }\n"
                        + "// -->\n" + "</style>" + "</head><body><h1>" + heading + "</h1>");

        String up = null;
        if (uri.length() > 1) {
            String u = uri.substring(0, uri.length() - 1);
            int slash = u.lastIndexOf('/');
            if (slash >= 0 && slash < u.length()) {
                up = uri.substring(0, slash + 1);
            }
        }

        List<String> files = Arrays.asList(f.list(new FilenameFilter() {

            @Override
            public boolean accept(File dir, String name) {
                return new File(dir, name).isFile();
            }
        }));
        Collections.sort(files);
        List<String> directories = Arrays.asList(f.list(new FilenameFilter() {

            @Override
            public boolean accept(File dir, String name) {
                return new File(dir, name).isDirectory();
            }
        }));
        Collections.sort(directories);
        if (up != null || directories.size() + files.size() > 0) {
            msg.append("<ul>");
            if (up != null || directories.size() > 0) {
                msg.append("<section class=\"directories\">");
                if (up != null) {
                    msg.append("<li><a rel=\"directory\" href=\"").append(up).append("\"><span class=\"dirname\">..</span></a></b></li>");
                }
                for (String directory : directories) {
                    String dir = directory + "/";
                    msg.append("<li><a rel=\"directory\" href=\"").append(encodeUri(uri + dir)).append("\"><span class=\"dirname\">").append(dir)
                            .append("</span></a></b></li>");
                }
                msg.append("</section>");
            }
            if (files.size() > 0) {
                msg.append("<section class=\"files\">");
                for (String file : files) {
                    msg.append("<li><a href=\"").append(encodeUri(uri + file)).append("\"><span class=\"filename\">").append(file).append("</span></a>");
                    File curFile = new File(f, file);
                    long len = curFile.length();
                    msg.append("&nbsp;<span class=\"filesize\">(");
                    if (len < 1024) {
                        msg.append(len).append(" bytes");
                    } else if (len < 1024 * 1024) {
                        msg.append(len / 1024).append(".").append(len % 1024 / 10 % 100).append(" KB");
                    } else {
                        msg.append(len / (1024 * 1024)).append(".").append(len % (1024 * 1024) / 10000 % 100).append(" MB");
                    }
                    msg.append(")</span></li>");
                }
                msg.append("</section>");
            }
            msg.append("</ul>");
        }
        msg.append("</body></html>");
        return msg.toString();
    }

    private static Response newFixedLengthResponse(IStatus status, String mimeType, String message) {
        Response response = superNewFixedLengthResponse(status, mimeType, message);
        response.addHeader("Accept-Ranges", "bytes");
        return response;
    }

    private static Response respond(Map<String, String> headers, IHTTPSession session, String uri, Map<String, WebServerPlugin> mimeTypeHandlers, Map<String, String> MIME_TYPES, List<File> rootDirs) {
        // Remove URL arguments
        uri = uri.trim().replace(File.separatorChar, '/');
        if (uri.indexOf('?') >= 0) {
            uri = uri.substring(0, uri.indexOf('?'));
        }

        // Prohibit getting out of current directory
        if (uri.contains("../")) {
            return getForbiddenResponse("Won't serve ../ for security reasons.");
        }

        boolean canServeUri = false;
        File homeDir = null;
        for (int i = 0; !canServeUri && i < rootDirs.size(); i++) {
            homeDir = rootDirs.get(i);
            canServeUri = canServeUri(uri, homeDir, mimeTypeHandlers, MIME_TYPES);
        }
        if (!canServeUri) {
            return getNotFoundResponse();
        }

        // Browsers get confused without '/' after the directory, send a
        // redirect.
        File f = new File(homeDir, uri);
        if (f.isDirectory() && !uri.endsWith("/")) {
            uri += "/";
            Response res =
                    newFixedLengthResponse(Status.REDIRECT, SridharFunctionalWebServer.MIME_HTML, "<html><body>Redirected: <a href=\"" + uri + "\">" + uri + "</a></body></html>");
            res.addHeader("Location", uri);
            return res;
        }

        if (f.isDirectory()) {
            // First look for index files (index.html, index.htm, etc) and if
            // none found, list the directory if readable.
            String indexFile = findIndexFileInDirectory(f);
            if (indexFile == null) {
                if (f.canRead()) {
                    // No index file, list the directory if it is readable
                    return newFixedLengthResponse(Status.OK, SridharFunctionalWebServer.MIME_HTML, listDirectory(uri, f));
                } else {
                    return getForbiddenResponse("No directory listing.");
                }
            } else {
                return respond(headers, session, uri + indexFile, mimeTypeHandlers, MIME_TYPES, rootDirs);
            }
        }

        String mimeTypeForFile = getMimeTypeForFile(uri, MIME_TYPES);
        WebServerPlugin plugin = mimeTypeHandlers.get(mimeTypeForFile);
        Response response = null;
        if (plugin != null && plugin.canServeUri(uri, homeDir)) {
            response = plugin.serveFile(uri, headers, session, f, mimeTypeForFile);
            if (response != null && response instanceof InternalRewrite) {
                InternalRewrite rewrite = (InternalRewrite) response;
                return respond(rewrite.getHeaders(), session, rewrite.getUri(), mimeTypeHandlers,MIME_TYPES, rootDirs);
            }
        } else {
            response = serveFile(uri, headers, f, mimeTypeForFile);
        }
        return response != null ? response : getNotFoundResponse();
    }


    private static boolean canServeUri(String uri, File homeDir,Map<String, WebServerPlugin> mimeTypeHandlers, Map<String, String> MIME_TYPES) {
        boolean canServeUri;
        File f = new File(homeDir, uri);
        canServeUri = f.exists();
        if (!canServeUri) {
            String mimeTypeForFile = getMimeTypeForFile(uri, MIME_TYPES);
            WebServerPlugin plugin = mimeTypeHandlers.get(mimeTypeForFile);
            if (plugin != null) {
                canServeUri = plugin.canServeUri(uri, homeDir);
            }
        }
        return canServeUri;
    }


    /**
     * Serves file from homeDir and its' subdirectories (only). Uses only URI,
     * ignores all headers and HTTP parameters.
     */
    private static Response serveFile(String uri, Map<String, String> header, File file, String mime) {
        Response res;
        try {
            // Calculate etag
            String etag = Integer.toHexString((file.getAbsolutePath() + file.lastModified() + "" + file.length()).hashCode());

            // Support (simple) skipping:
            long startFrom = 0;
            long endAt = -1;
            String range = header.get("range");
            if (range != null) {
                if (range.startsWith("bytes=")) {
                    range = range.substring("bytes=".length());
                    int minus = range.indexOf('-');
                    try {
                        if (minus > 0) {
                            startFrom = Long.parseLong(range.substring(0, minus));
                            endAt = Long.parseLong(range.substring(minus + 1));
                        }
                    } catch (NumberFormatException ignored) {
                    }
                }
            }

            // get if-range header. If present, it must match etag or else we
            // should ignore the range request
            String ifRange = header.get("if-range");
            boolean headerIfRangeMissingOrMatching = (ifRange == null || etag.equals(ifRange));

            String ifNoneMatch = header.get("if-none-match");
            boolean headerIfNoneMatchPresentAndMatching = ifNoneMatch != null && (ifNoneMatch.equals("*") || ifNoneMatch.equals(etag));

            // Change return code and add Content-Range header when skipping is
            // requested
            long fileLen = file.length();

            if (headerIfRangeMissingOrMatching && range != null && startFrom >= 0 && startFrom < fileLen) {
                // range request that matches current etag
                // and the startFrom of the range is satisfiable
                if (headerIfNoneMatchPresentAndMatching) {
                    // range request that matches current etag
                    // and the startFrom of the range is satisfiable
                    // would return range from file
                    // respond with not-modified
                    res = newFixedLengthResponse(Status.NOT_MODIFIED, mime, "");
                    res.addHeader("ETag", etag);
                } else {
                    if (endAt < 0) {
                        endAt = fileLen - 1;
                    }
                    long newLen = endAt - startFrom + 1;
                    if (newLen < 0) {
                        newLen = 0;
                    }

                    FileInputStream fis = new FileInputStream(file);
                    fis.skip(startFrom);

                    res = newFixedLengthResponse(Status.PARTIAL_CONTENT, mime, fis, newLen);
                    res.addHeader("Accept-Ranges", "bytes");
                    res.addHeader("Content-Length", "" + newLen);
                    res.addHeader("Content-Range", "bytes " + startFrom + "-" + endAt + "/" + fileLen);
                    res.addHeader("ETag", etag);
                }
            } else {

                if (headerIfRangeMissingOrMatching && range != null && startFrom >= fileLen) {
                    // return the size of the file
                    // 4xx responses are not trumped by if-none-match
                    res = newFixedLengthResponse(Status.RANGE_NOT_SATISFIABLE, SridharFunctionalWebServer.MIME_PLAINTEXT, "");
                    res.addHeader("Content-Range", "bytes */" + fileLen);
                    res.addHeader("ETag", etag);
                } else if (range == null && headerIfNoneMatchPresentAndMatching) {
                    // full-file-fetch request
                    // would return entire file
                    // respond with not-modified
                    res = newFixedLengthResponse(Status.NOT_MODIFIED, mime, "");
                    res.addHeader("ETag", etag);
                } else if (!headerIfRangeMissingOrMatching && headerIfNoneMatchPresentAndMatching) {
                    // range request that doesn't match current etag
                    // would return entire (different) file
                    // respond with not-modified

                    res = newFixedLengthResponse(Status.NOT_MODIFIED, mime, "");
                    res.addHeader("ETag", etag);
                } else {
                    // supply the file
                    res = newFixedFileResponse(file, mime);
                    res.addHeader("Content-Length", "" + fileLen);
                    res.addHeader("ETag", etag);
                }
            }
        } catch (IOException ioe) {
            res = getForbiddenResponse("Reading file failed.");
        }

        return res;
    }
    
    private static Response serve(IHTTPSession session, Map<String, WebServerPlugin> mimeTypeHandlers, Map<String, String> MIME_TYPES, boolean quiet, List<File> rootDirs) {
        Map<String, String> header = session.getHeaders();
        Map<String, String> parms = session.getParms();
        String uri = session.getUri();

        if (!quiet) {
            System.out.println(session.getMethod() + " '" + uri + "' ");

            Iterator<String> e = header.keySet().iterator();
            while (e.hasNext()) {
                String value = e.next();
                System.out.println("  HDR: '" + value + "' = '" + header.get(value) + "'");
            }
            e = parms.keySet().iterator();
            while (e.hasNext()) {
                String value = e.next();
                System.out.println("  PRM: '" + value + "' = '" + parms.get(value) + "'");
            }
        }

        for (File homeDir : rootDirs) {
            // Make sure we won't die of an exception later
            if (!homeDir.isDirectory()) {
                return getInternalErrorResponse("given path is not a directory (" + homeDir + ").");
            }
        }
        return respond(Collections.unmodifiableMap(header), session, uri, mimeTypeHandlers, MIME_TYPES, rootDirs);
    }

    private static Response newFixedFileResponse(File file, String mime) throws FileNotFoundException {
        Response res;
        res = newFixedLengthResponse(Status.OK, mime, new FileInputStream(file), (int) file.length());
        res.addHeader("Accept-Ranges", "bytes");
        return res;
    }
    
    private static interface WebServerPlugin {

        boolean canServeUri(String uri, File rootDir);

        void initialize(ImmutableMap<String, String> commandLineOptions);

        Response serveFile(String uri, Map<String, String> headers, IHTTPSession session, File file, String mimeType);
    }
    
    private static class InternalRewrite extends Response {

        private final String uri;

        private final Map<String, String> headers;

        public InternalRewrite(Map<String, String> headers, String uri) {
            super(Status.OK, MIME_HTML, new ByteArrayInputStream(new byte[0]), 0);
            this.headers = headers;
            this.uri = uri;
        }

        public Map<String, String> getHeaders() {
            return this.headers;
        }

        public String getUri() {
            return this.uri;
        }
    }
    private static interface WebServerPluginInfo {

        String[] getIndexFilesForMimeType(String mime);

        String[] getMimeTypes();

        WebServerPlugin getWebServerPlugin(String mimeType);
    }
    
    /** Starts as a standalone file server and waits for Enter. */
    public static void main(final String[] args) {
		final String host = getHost(args);
		final int port = getPort(args);
		final boolean quiet = isQuietMode(args);
		final ImmutableList<File> wwwroots = getRootDirs(args);

		listenOnPortInSeparateThread(args, host, port, quiet, wwwroots);
    }

	private static void listenOnPortInSeparateThread(final String[] args,
			final String host, final int port, final boolean quiet,
			final ImmutableList<File> wwwroots) {
		new Thread () {
			@Override
			public void run() {
				serveFilesOnPort(host, port, wwwroots, quiet,
						createPluginsMap(args, host, port, quiet, wwwroots));				
			}
		
		}.start();
	}

	private static void serveFilesOnPort(String host,
			int port, ImmutableList<File> wwwroots, boolean quiet, ImmutableMap<String, WebServerPlugin> createPluginsMap) {
		ServerSocket serverSocket = null;
		AsyncRunner asyncRunner = new DefaultAsyncRunner();
		try {
		    serverSocket = createServerSocket(null, host, port);
		} catch (IOException ioe) {
		    System.err.println("Couldn't start server:\n" + ioe);
		    System.exit(-1);
		}
		Thread myThread = SridharFunctionalWebServer.createThread(
				createPluginsMap,
				SridharFunctionalWebServer.SOCKET_READ_TIMEOUT,
				new DefaultTempFileManagerFactory(), asyncRunner, checkNotNull(serverSocket),
				quiet, wwwroots);
		
		System.out.println("Server started, Hit Enter to stop.\n");
		
		try {
		    System.in.read();
		} catch (Throwable ignored) {
		}
		
		SridharFunctionalWebServer.stop(serverSocket, myThread, asyncRunner);
		System.out.println("Server stopped.\n");
	}
}
