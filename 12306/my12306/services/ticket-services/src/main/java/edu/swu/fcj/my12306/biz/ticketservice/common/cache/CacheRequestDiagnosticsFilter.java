package edu.swu.fcj.my12306.biz.ticketservice.common.cache;
import jakarta.servlet.*;
import org.springframework.stereotype.Component;
import java.io.IOException;
@Component
public class CacheRequestDiagnosticsFilter implements Filter {
    private final CacheDiagnostics diagnostics;
    public CacheRequestDiagnosticsFilter(CacheDiagnostics diagnostics){this.diagnostics=diagnostics;}
    @Override public void doFilter(ServletRequest request,ServletResponse response,FilterChain chain)throws IOException,ServletException {
        try(var scope=diagnostics.request()){chain.doFilter(request,response);}
        catch(IOException|ServletException|RuntimeException ex){throw ex;}
        catch(Exception ex){throw new ServletException(ex);}
    }
}
