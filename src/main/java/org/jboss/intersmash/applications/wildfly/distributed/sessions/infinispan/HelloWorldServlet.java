package org.jboss.intersmash.applications.wildfly.distributed.sessions.infinispan;

import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.io.PrintWriter;

@SuppressWarnings("serial")
@WebServlet("/HelloWorld")
public class HelloWorldServlet extends HttpServlet {

	@Override
	protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
		HttpSession session = req.getSession(true);
		session.setAttribute("mySessionData", "********* ********* ********* ********* ********* "
				+ "********* ********* ********* ********* ********* ");
		resp.setContentType("text/html");

		PrintWriter writer = resp.getWriter();
		writer.print("Session: " + session.getId());
		writer.close();
	}

}
