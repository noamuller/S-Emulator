package server.api;

import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.MultipartConfig;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.Part;
import server.core.EngineFacade;
import server.core.ProgramInfo;
import server.core.SimpleJson;

import java.io.*;
import java.nio.file.Files;
import java.util.Map;

@WebServlet("/api/programs/upload")
@MultipartConfig
public class UploadProgramServlet extends HttpServlet {

    private EngineFacade facade(HttpServletRequest req) {
        return (EngineFacade) getServletContext().getAttribute("facade");
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp)
            throws ServletException, IOException {

        resp.setContentType("application/json;charset=UTF-8");
        try (PrintWriter out = resp.getWriter()) {

            Part filePart = req.getPart("file");
            if (filePart == null || filePart.getSize() == 0) {
                resp.setStatus(HttpServletResponse.SC_BAD_REQUEST);
                SimpleJson.write(out, Map.of("ok", false, "error", "missing 'file' part"));
                return;
            }

            // Sanitize XML stream: strip UTF-8 BOM and any leading whitespace before '<'
            File tmp = Files.createTempFile("program-", ".xml").toFile();
            try (InputStream raw = filePart.getInputStream();
                 OutputStream cleaned = new BufferedOutputStream(new FileOutputStream(tmp))) {

                sanitizeXml(raw, cleaned); // <-- writes only clean XML to tmp
            }

            // Let the engine load & register program (your existing parser reads the File)
            ProgramInfo info = facade(req).loadProgram(tmp.getAbsolutePath());


            SimpleJson.write(out, Map.of("ok", true, "program", Map.of(
                    "id", info.id(),
                    "name", info.name(),
                    "maxDegree", info.maxDegree()
            )));

        } catch (Exception e) {
            resp.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            try (PrintWriter out = resp.getWriter()) {
                SimpleJson.write(out, Map.of("ok", false, "error", e.getMessage()));
            }
        }
    }

    /**
     * Copy XML from 'in' to 'out', removing a UTF-8 BOM if present and
     * skipping any leading whitespace/newlines before the first '<'.
     */
    private static void sanitizeXml(InputStream in, OutputStream out) throws IOException {
        PushbackInputStream pin = new PushbackInputStream(new BufferedInputStream(in), 3);

        // Strip UTF-8 BOM if present (EF BB BF)
        byte[] bom = new byte[3];
        int n = pin.read(bom, 0, 3);
        boolean hasBom = (n == 3 && (bom[0] & 0xFF) == 0xEF && (bom[1] & 0xFF) == 0xBB && (bom[2] & 0xFF) == 0xBF);
        if (!hasBom) {
            if (n > 0) pin.unread(bom, 0, n);
        }

        // Skip any leading whitespace before first '<'
        int b;
        while ((b = pin.read()) != -1) {
            if (!Character.isWhitespace(b)) {
                if (b != '<') {
                    // If the very first non-space isn't '<', push it back anyway so parser errors clearly
                    pin.unread(b);
                } else {
                    out.write('<');
                }
                break;
            }
        }

        // Copy the rest
        byte[] buf = new byte[8192];
        int r;
        while ((r = pin.read(buf)) != -1) {
            out.write(buf, 0, r);
        }
        out.flush();
    }
}
