package org.eclipse.equinox.http.jetty.ms;

import java.io.File;
import java.io.IOError;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Paths;
import java.util.Dictionary;
import java.util.Hashtable;

import javax.servlet.Servlet;
import javax.servlet.ServletConfig;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpSessionEvent;
import javax.servlet.http.HttpSessionIdListener;
import javax.servlet.http.HttpSessionListener;

import org.eclipse.equinox.http.servlet.HttpServiceServlet;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.SecureRequestCustomizer;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.SslConnectionFactory;
import org.eclipse.jetty.server.session.HouseKeeper;
import org.eclipse.jetty.server.session.SessionHandler;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;
import org.osgi.framework.Constants;
import org.osgi.framework.ServiceRegistration;
import org.osgi.service.cm.ConfigurationException;
import org.osgi.service.cm.ManagedService;

public class Activator implements BundleActivator, ManagedService {

	private static final int DEFAULT_IDLE_TIMEOUT = 30000;
	
	private static final String INTERNAL_CONTEXT_CLASSLOADER = "org.eclipse.equinox.http.jetty.internal.ContextClassLoader"; //$NON-NLS-1$
	
	private static final String DIR_PREFIX = "pid_"; //$NON-NLS-1$
	
	private static final String CONTEXT_TEMPDIR = "javax.servlet.context.tempdir"; //$NON-NLS-1$
	
	private static final String JETTY_WORK_DIR = "jettywork"; //$NON-NLS-1$
	
	// Jetty will use a basic stderr logger if no other logging mechanism is provided.
	// This setting can be used to over-ride the stderr logger threshold(and only this default logger)
	// Valid values are in increasing threshold: "debug", "info", "warn", "error", and "off"
	// (default threshold is "warn")
	private static final String LOG_STDERR_THRESHOLD = "org.eclipse.equinox.http.jetty.log.stderr.threshold"; //$NON-NLS-1$

	private static final String DEFAULT_PID = "org.eclipse.equinox.http.jetty.ms"; //$NON-NLS-1$
	
	// OSGi Http Service suggest these properties for setting the default ports
	private static final String ORG_OSGI_SERVICE_HTTP_PORT = "org.osgi.service.http.port"; //$NON-NLS-1$
	
	private static final String ORG_OSGI_SERVICE_HTTP_PORT_SECURE = "org.osgi.service.http.port.secure"; //$NON-NLS-1$

	private File jettyWorkDir;
	
	private ServiceRegistration<ManagedService> registration;
	
	private Server httpServer;
	
	private BundleContext ctx;
	
	private void stopHttpServer() throws Exception {
		if (httpServer != null) {
			httpServer.stop();
			httpServer = null;
			File contextWorkDir = new File(jettyWorkDir, "");
			if (!deleteDirectory(contextWorkDir)) {
				throw new IOException("Can't delete directory " + contextWorkDir.getAbsolutePath());
			}
			jettyWorkDir = null;
		}
	}
	
	@Override
	public void updated(Dictionary<String, ?> properties) throws ConfigurationException {
		try {
			stopHttpServer();
		} catch (Exception e) {
			throw new ConfigurationException(DEFAULT_PID, e.getMessage(), e);
		}
		
		if (properties == null) {
			return;
		}
		
		final Server server = new Server(new QueuedThreadPool(Details.getInt(properties, JettyConstants.HTTP_MAXTHREADS, 200), Details.getInt(properties, JettyConstants.HTTP_MINTHREADS, 8)));

		/**
		 * May be modified by createHttp(s)Connector.
		 */
		final HttpConfiguration http_config = new HttpConfiguration();

		final ServerConnector httpConnector = createHttpConnector(properties, server, http_config);

		final ServerConnector httpsConnector;
		try {
			httpsConnector = createHttpsConnector(properties, server, http_config);
		} catch (IOError e) {
			throw new ConfigurationException(DEFAULT_PID, e.getMessage(), e);
		}

		if (httpConnector != null) {
			try {
				httpConnector.open();
			} catch (IOException e) {
				throw new ConfigurationException(DEFAULT_PID, e.getMessage(), e);
			}
			server.addConnector(httpConnector);
		}

		if (httpsConnector != null) {
			try {
				httpsConnector.open();
			} catch (IOException e) {
				throw new ConfigurationException(DEFAULT_PID, e.getMessage(), e);
			}
			server.addConnector(httpsConnector);
		}

		final ServletHolder holder = new ServletHolder(new InternalHttpServiceServlet());
		holder.setInitOrder(0);
		holder.setInitParameter(Constants.SERVICE_VENDOR, "Eclipse.org"); //$NON-NLS-1$
		holder.setInitParameter(Constants.SERVICE_DESCRIPTION, "Equinox Jetty-based Http Service"); //$NON-NLS-1$

		final String multipartServletName = "Equinox Jetty-based Http Service - Multipart Servlet"; //$NON-NLS-1$

		holder.setInitParameter("multipart.servlet.name", multipartServletName); //$NON-NLS-1$

		if (httpConnector != null) {
			int port = httpConnector.getLocalPort();
			if (port == -1)
				port = httpConnector.getPort();
			holder.setInitParameter(JettyConstants.HTTP_PORT, Integer.toString(port));
			final String host = httpConnector.getHost();
			if (host != null)
				holder.setInitParameter(JettyConstants.HTTP_HOST, host);
		}
		if (httpsConnector != null) {
			int port = httpsConnector.getLocalPort();
			if (port == -1)
				port = httpsConnector.getPort();
			holder.setInitParameter(JettyConstants.HTTPS_PORT, Integer.toString(port));
			final String host = httpsConnector.getHost();
			if (host != null)
				holder.setInitParameter(JettyConstants.HTTPS_HOST, host);
		}
		final String otherInfo = Details.getString(properties, JettyConstants.OTHER_INFO, null);
		if (otherInfo != null)
			holder.setInitParameter(JettyConstants.OTHER_INFO, otherInfo);

		final ServletContextHandler httpContext;
		try {
			httpContext = createHttpContext(properties);
		} catch (IOException e) {
			throw new ConfigurationException(DEFAULT_PID, e.getMessage(), e);
		}
		holder.setInitParameter(JettyConstants.CONTEXT_PATH, httpContext.getContextPath());
		httpContext.addServlet(holder, "/*"); //$NON-NLS-1$
		server.setHandler(httpContext);

		try {
			server.start();
			final SessionHandler sessionManager = httpContext.getSessionHandler();
			sessionManager.addEventListener((HttpSessionIdListener) holder.getServlet());
			final HouseKeeper houseKeeper = server.getSessionIdManager().getSessionHouseKeeper();
			houseKeeper.setIntervalSec(Details.getLong(properties, JettyConstants.HOUSEKEEPER_INTERVAL, houseKeeper.getIntervalSec()));
		} catch (Exception e) {
			throw new ConfigurationException(DEFAULT_PID, e.getMessage(), e);
		}
		
		httpServer = server;
	}

	@Override
	public synchronized void start(BundleContext context) throws Exception {
		
		ctx = context;
		
		EquinoxStdErrLog.setThresholdLogger(context.getProperty(LOG_STDERR_THRESHOLD));
		
		final Dictionary<String, Object> dictionary = new Hashtable<>();
		dictionary.put(Constants.SERVICE_PID, DEFAULT_PID);

		registration = context.registerService(ManagedService.class, this, dictionary);
	}

	@Override
	public synchronized void stop(BundleContext context) throws Exception {
		registration.unregister();
		registration = null;
		stopHttpServer();
		ctx = null;
	}

	private ServerConnector createHttpsConnector(Dictionary<String, ?> dictionary, Server server, HttpConfiguration http_config) {
		ServerConnector httpsConnector = null;
		if (Details.getBoolean(dictionary, JettyConstants.HTTPS_ENABLED, false)) {
			// SSL Context Factory for HTTPS and SPDY
			final SslContextFactory.Server sslContextFactory = new SslContextFactory.Server();

			//Not sure if the next tree are properly migrated from jetty 8...
			final String strPath = Details.getString(dictionary, JettyConstants.SSL_KEYSTORE_PATH, null);
			if (strPath != null) {
				sslContextFactory.setKeyStorePath(Paths.get(strPath).toAbsolutePath().toString());
			} else {
				sslContextFactory.setKeyStorePath(null);
			}
			sslContextFactory.setKeyStorePassword(Details.getString(dictionary, JettyConstants.SSL_KEYSTORE_PASSWORD, null));
			sslContextFactory.setKeyManagerPassword(Details.getString(dictionary, JettyConstants.SSL_KEY_PASSWORD, null));
			sslContextFactory.setKeyStoreType(Details.getString(dictionary, JettyConstants.SSL_KEYSTORE_TYPE, "PKCS12")); //$NON-NLS-1$
			sslContextFactory.setProtocol(Details.getString(dictionary, JettyConstants.SSL_PROTOCOL, "TLS")); //$NON-NLS-1$
			sslContextFactory.setWantClientAuth(Details.getBoolean(dictionary, JettyConstants.SSL_WANTCLIENTAUTH, false));
			sslContextFactory.setNeedClientAuth(Details.getBoolean(dictionary, JettyConstants.SSL_NEEDCLIENTAUTH, false));
			// TODO trust store.

			// HTTPS Configuration
			final HttpConfiguration https_config = new HttpConfiguration(http_config);
			https_config.addCustomizer(new SecureRequestCustomizer());

			// HTTPS connector
			httpsConnector = new ServerConnector(server, new SslConnectionFactory(sslContextFactory, "http/1.1"), new HttpConnectionFactory(https_config)); //$NON-NLS-1$
			int httpsPort = Details.getInt(dictionary, JettyConstants.HTTPS_PORT, -1);
			if (httpsPort == -1) {
				httpsPort = Details.getInt(dictionary, ORG_OSGI_SERVICE_HTTP_PORT_SECURE, 443);
			}
			httpsConnector.setPort(httpsPort);
			httpsConnector.setHost(Details.getString(dictionary, JettyConstants.HTTPS_HOST, null));
		}
		return httpsConnector;
	}

	private ServerConnector createHttpConnector(Dictionary<String, ?> dictionary, Server server, HttpConfiguration http_config) {
		ServerConnector httpConnector = null;
		if (Details.getBoolean(dictionary, JettyConstants.HTTP_ENABLED, true)) {
			// HTTP Configuration
			if (Details.getBoolean(dictionary, JettyConstants.HTTPS_ENABLED, false)) {
				http_config.setSecureScheme("https"); //$NON-NLS-1$
				int httpsPort = Details.getInt(dictionary, JettyConstants.HTTPS_PORT, -1);
				if (httpsPort == -1) {
					httpsPort = Details.getInt(dictionary, ORG_OSGI_SERVICE_HTTP_PORT_SECURE, 443);
				}
				http_config.setSecurePort(httpsPort);
			}
			// HTTP connector
			httpConnector = new ServerConnector(server, new HttpConnectionFactory(http_config));
			int httpPort = Details.getInt(dictionary, JettyConstants.HTTP_PORT, -1);
			if (httpPort == -1) {
				httpPort = Details.getInt(dictionary, ORG_OSGI_SERVICE_HTTP_PORT, 80);
			}
			httpConnector.setPort(httpPort);
			httpConnector.setHost(Details.getString(dictionary, JettyConstants.HTTP_HOST, null));
			httpConnector.setIdleTimeout(DEFAULT_IDLE_TIMEOUT);
		}
		return httpConnector;
	}

	private ServletContextHandler createHttpContext(Dictionary<String, ?> dictionary) throws IOException {
		jettyWorkDir = new File(ctx.getDataFile(""), JETTY_WORK_DIR); //$NON-NLS-1$
		if (!jettyWorkDir.mkdir()) {
			throw new IOException("Can't create directory " + jettyWorkDir.getPath());
		}
		
		ServletContextHandler httpContext = new ServletContextHandler();
		// hack in the mime type for xsd until jetty fixes it (bug 393218)
		httpContext.getMimeTypes().addMimeMapping("xsd", "application/xml"); //$NON-NLS-1$ //$NON-NLS-2$
		httpContext.setAttribute(INTERNAL_CONTEXT_CLASSLOADER, Thread.currentThread().getContextClassLoader());
		httpContext.setClassLoader(this.getClass().getClassLoader());
		httpContext.setContextPath(Details.getString(dictionary, JettyConstants.CONTEXT_PATH, "/")); //$NON-NLS-1$

		File contextWorkDir = new File(jettyWorkDir, DIR_PREFIX + dictionary.get(Constants.SERVICE_PID).hashCode());
		contextWorkDir.mkdir();
		httpContext.setAttribute(CONTEXT_TEMPDIR, contextWorkDir);
		SessionHandler handler = new SessionHandler();
		handler.setMaxInactiveInterval(Details.getInt(dictionary, JettyConstants.CONTEXT_SESSIONINACTIVEINTERVAL, -1));
		httpContext.setSessionHandler(handler);

		return httpContext;
	}

	public static class InternalHttpServiceServlet implements HttpSessionListener, HttpSessionIdListener, Servlet {
		//		private static final long serialVersionUID = 7477982882399972088L;
		private final Servlet httpServiceServlet = new HttpServiceServlet();
		private ClassLoader contextLoader;
		private final Method sessionDestroyed;
		private final Method sessionIdChanged;

		public InternalHttpServiceServlet() {
			Class<?> clazz = httpServiceServlet.getClass();

			try {
				sessionDestroyed = clazz.getMethod("sessionDestroyed", new Class<?>[] {String.class}); //$NON-NLS-1$
			} catch (Exception e) {
				throw new IllegalStateException(e);
			}
			try {
				sessionIdChanged = clazz.getMethod("sessionIdChanged", new Class<?>[] {String.class}); //$NON-NLS-1$
			} catch (Exception e) {
				throw new IllegalStateException(e);
			}
		}

		@Override
		public void init(ServletConfig config) throws ServletException {
			ServletContext context = config.getServletContext();
			contextLoader = (ClassLoader) context.getAttribute(INTERNAL_CONTEXT_CLASSLOADER);

			Thread thread = Thread.currentThread();
			ClassLoader current = thread.getContextClassLoader();
			thread.setContextClassLoader(contextLoader);
			try {
				httpServiceServlet.init(config);
			} finally {
				thread.setContextClassLoader(current);
			}
		}

		@Override
		public void destroy() {
			Thread thread = Thread.currentThread();
			ClassLoader current = thread.getContextClassLoader();
			thread.setContextClassLoader(contextLoader);
			try {
				httpServiceServlet.destroy();
			} finally {
				thread.setContextClassLoader(current);
			}
			contextLoader = null;
		}

		@Override
		public void service(ServletRequest req, ServletResponse res) throws ServletException, IOException {
			Thread thread = Thread.currentThread();
			ClassLoader current = thread.getContextClassLoader();
			thread.setContextClassLoader(contextLoader);
			try {
				httpServiceServlet.service(req, res);
			} finally {
				thread.setContextClassLoader(current);
			}
		}

		@Override
		public ServletConfig getServletConfig() {
			return httpServiceServlet.getServletConfig();
		}

		@Override
		public String getServletInfo() {
			return httpServiceServlet.getServletInfo();
		}

		@Override
		public void sessionCreated(HttpSessionEvent event) {
			// Nothing to do.
		}

		@Override
		public void sessionDestroyed(HttpSessionEvent event) {
			Thread thread = Thread.currentThread();
			ClassLoader current = thread.getContextClassLoader();
			thread.setContextClassLoader(contextLoader);
			try {
				sessionDestroyed.invoke(httpServiceServlet, event.getSession().getId());
			} catch (IllegalAccessException | IllegalArgumentException e) {
				// not likely
			} catch (InvocationTargetException e) {
				throw new RuntimeException(e.getCause());
			} finally {
				thread.setContextClassLoader(current);
			}
		}

		@Override
		public void sessionIdChanged(HttpSessionEvent event, String oldSessionId) {
			Thread thread = Thread.currentThread();
			ClassLoader current = thread.getContextClassLoader();
			thread.setContextClassLoader(contextLoader);
			try {
				sessionIdChanged.invoke(httpServiceServlet, oldSessionId);
			} catch (IllegalAccessException | IllegalArgumentException e) {
				// not likely
			} catch (InvocationTargetException e) {
				throw new RuntimeException(e.getCause());
			} finally {
				thread.setContextClassLoader(current);
			}
		}
	}

	// deleteDirectory is a convenience method to recursively delete a directory
	private static boolean deleteDirectory(File directory) {
		if (directory.exists() && directory.isDirectory()) {
			File[] files = directory.listFiles();
			for (File file : files) {
				if (file.isDirectory()) {
					deleteDirectory(file);
				} else {
					file.delete();
				}
			}
		}
		return directory.delete();
	}

}
