package org.foo.hello.world;

import java.io.IOException;

import javax.servlet.Servlet;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ServiceScope;

@Component(service = Servlet.class, scope = ServiceScope.PROTOTYPE, property = "osgi.http.whiteboard.servlet.pattern=/hello")
public class HelloWorldServlet extends HttpServlet {

	private static final long serialVersionUID = -3502284765690010384L;

	@Override
	protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
		resp.getWriter().println("Hello World!");
	}

}
