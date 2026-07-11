package io.quarkiverse.shim.devmode;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;

@Path("/greet")
public class DevGreetResource {

    @GET
    public String greet() {
        return new DevGreeter().greet("world");
    }
}
