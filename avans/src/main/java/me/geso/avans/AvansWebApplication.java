package me.geso.avans;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.StringWriter;
import java.nio.charset.Charset;
import java.util.Map;
import java.util.Optional;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import lombok.SneakyThrows;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.mustachejava.DefaultMustacheFactory;
import com.github.mustachejava.Mustache;

/**
 * You should create this object per HTTP request.
 * 
 * @author tokuhirom
 *
 */
public abstract class AvansWebApplication implements Closeable {
	private AvansRequest request;
	private HttpServletResponse servletResponse;
	private Map<String, String> args;
	private static final Charset UTF8 = Charset.forName("UTF-8");

	public AvansWebApplication(HttpServletRequest servletRequest,
			HttpServletResponse servletResponse) throws IOException {
		this.request = new AvansRequest(servletRequest);
		this.servletResponse = servletResponse;
	}

	protected HttpServletResponse getServletResponse() {
		return this.servletResponse;
	}

	public void setDefaultCharacterEncoding() {
		this.servletResponse.setCharacterEncoding("UTF-8");
		;
	}

	public void run() throws IOException {
		this.setDefaultCharacterEncoding();

		AvansResponse res = this.makeResponse();
		this.AFTER_DISPATCH(res);
		res.write(servletResponse);
	}

	private AvansResponse makeResponse() {
		{
			Optional<AvansResponse> maybeResponse = this.BEFORE_DISPATCH();
			if (maybeResponse.isPresent()) {
				return maybeResponse.get();
			}
		}

		AvansResponse res = this.dispatch();
		if (res == null) {
			throw new RuntimeException("dispatch method must not return NULL");
		}
		return res;
	}

	public AvansRequest getRequest() {
		return this.request;
	}

	public abstract AvansResponse dispatch();

	protected void setArgs(Map<String, String> captured) {
		this.args = captured;
	}

	/**
	 * Get a path parameter.
	 * 
	 * @param name
	 * @return
	 */
	public String getArg(String name) {
		String arg = this.args.get(name);
		if (arg == null) {
			throw new RuntimeException("Missing mandatory argument: " + name);
		}
		return arg;
	}

	/**
	 * Get a path parameter in long.
	 * 
	 * @param name
	 * @return
	 */
	public long getLongArg(String name) {
		String arg = this.getArg(name);
		return Long.parseLong(arg);
	}

	/**
	 * Get a path parameter in int.
	 * 
	 * @param name
	 * @return
	 */
	public int getIntArg(String name) {
		String arg = this.getArg(name);
		return Integer.parseInt(arg);
	}

	/**
	 * Get a path parameter in String. But this doesn't throws exception if the
	 * value doesn't exists.
	 * 
	 * @param name
	 * @return
	 */
	public Optional<String> getOptionalArg(String name) {
		String arg = this.args.get(name);
		return Optional.ofNullable(arg);
	}

	/**
	 * Create new "405 Method Not Allowed" response in JSON.
	 * 
	 * @return
	 */
	public AvansResponse errorMethodNotAllowed() {
		return this.renderError(405, "Method Not Allowed");
	}

	/**
	 * Create new "403 Forbidden" response in JSON.
	 * 
	 * @return
	 */
	public AvansResponse errorForbidden() {
		return this.renderError(403, "Forbidden");
	}

	/**
	 * Create new "404 Not Found" response in JSON.
	 * 
	 * @return
	 */
	public AvansResponse errorNotFound() {
		return this.renderError(404, "Not Found");
	}

	/**
	 * Render the error response.
	 * 
	 * @param code
	 * @param message
	 * @return
	 */
	protected AvansResponse renderError(int code, String message) {
		AvansAPIResponse<String> apires = new AvansAPIResponse<>(code, message);

		AvansBytesResponse res = this.renderJSON(apires);
		res.setStatus(code);
		return res;
	}

	/**
	 * Create new redirect response. You can use relative url here.
	 * 
	 * @param location
	 * @return
	 */
	public AvansRedirectResponse redirect(String location) {
		return new AvansRedirectResponse(location);
	}

	/**
	 * Create new text/plain response.
	 * 
	 * @param text
	 * @return
	 */
	@SneakyThrows
	public AvansResponse renderTEXT(String text) {
		byte[] bytes = text.getBytes(UTF8);

		AvansBytesResponse res = new AvansBytesResponse();
		res.setContentType("text/plain; charset=utf-8");
		res.setBody(bytes);
		return res;
	}

	/**
	 * Rendering JSON by jackson.
	 * 
	 * @param obj
	 * @return
	 */
	@SneakyThrows
	public AvansBytesResponse renderJSON(Object obj) {
		ObjectMapper mapper = createObjectMapper();
		byte[] json = mapper.writeValueAsBytes(obj);

		AvansBytesResponse res = new AvansBytesResponse();
		res.setContentType("application/json; charset=utf-8");
		res.setContentLength(json.length);
		res.setBody(json);
		return res;
	}

	/**
	 * Create new ObjectMapper instance. You can override this method for
	 * customizing.
	 * 
	 * @return
	 */
	protected ObjectMapper createObjectMapper() {
		ObjectMapper mapper = new ObjectMapper();
		mapper.configure(JsonGenerator.Feature.ESCAPE_NON_ASCII, true);
		return mapper;
	}

	/**
	 * Create a response object by mustache template engine.
	 * 
	 * @param template
	 * @param context
	 * @return
	 */
	@SneakyThrows
	public AvansBytesResponse renderMustache(String template, Object context) {
		File tmplDir = this.getTemplateDirectory();
		DefaultMustacheFactory factory = new DefaultMustacheFactory(tmplDir);
		Mustache mustache = factory.compile(template);
		StringWriter writer = new StringWriter();
		mustache.execute(writer, context).flush();
		String bodyString = writer.toString();
		byte[] body = bodyString.getBytes(Charset.forName("UTF-8"));

		AvansBytesResponse res = new AvansBytesResponse();
		res.setContentType("text/html; charset=utf-8");
		res.setContentLength(body.length);
		res.setBody(body);
		return res;
	}

	public File getTemplateDirectory() {
		return new File(getBaseDirectory() + "/tmpl/");
	}

	/**
	 * Get project base directory. TODO: better jar location detection
	 * algorithm.
	 * 
	 * @return
	 */
	@SneakyThrows
	public String getBaseDirectory() {
		String baseDirectory = this.getClass().getProtectionDomain()
				.getCodeSource().getLocation().getPath();
		return baseDirectory;
	}

	/**
	 * This is a hook point. You can override this.
	 * 
	 * Use case:
	 * <ul>
	 * <li>Authentication before dispatching</li>
	 * </ul>
	 * 
	 * @return
	 */
	protected Optional<AvansResponse> BEFORE_DISPATCH() {
		// override me.
		return Optional.empty();
	}

	/**
	 * This is a hook point. You can override this method.
	 * 
	 * @param res
	 */
	protected void AFTER_DISPATCH(AvansResponse res) {
		return; // NOP
	}

}