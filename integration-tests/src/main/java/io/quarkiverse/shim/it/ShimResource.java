/*
* Licensed to the Apache Software Foundation (ASF) under one or more
* contributor license agreements.  See the NOTICE file distributed with
* this work for additional information regarding copyright ownership.
* The ASF licenses this file to You under the Apache License, Version 2.0
* (the "License"); you may not use this file except in compliance with
* the License.  You may obtain a copy of the License at
*
*      http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*/
package io.quarkiverse.shim.it;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;

@Path("/shim")
@ApplicationScoped
public class ShimResource {

    @GET
    public String hello() {
        return "Hello shim";
    }

    @GET
    @Path("/greet/{name}")
    public String greet(@PathParam("name") String name) {
        return new Greeter().greet(name);
    }

    @GET
    @Path("/answer")
    public int answer() {
        return Greeter.answer();
    }

    @GET
    @Path("/shout/{name}")
    public String shout(@PathParam("name") String name) {
        return new Greeter().shout(name);
    }

    @GET
    @Path("/task/{input}")
    public String task(@PathParam("input") String input) {
        Task task = new Task();
        task.run(input);
        return String.join(",", task.log());
    }

    @GET
    @Path("/widget/{name}")
    public String widget(@PathParam("name") String name) {
        return new Widget(name).name();
    }

    @GET
    @Path("/format")
    public String format() {
        return Formatter.format(7) + "|" + Formatter.format("x");
    }
}
