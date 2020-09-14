/*
 * Copyright 2017-present  Koordinierungsstelle für IT-Standards (KoSIT)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package de.kosit.validationtool.daemon;

import static io.restassured.RestAssured.given;
import static org.apache.http.HttpStatus.SC_NOT_ACCEPTABLE;
import static org.apache.http.HttpStatus.SC_OK;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Paths;

import org.apache.commons.io.IOUtils;
import org.apache.http.HttpStatus;
import org.junit.Test;

import de.kosit.validationtool.impl.Helper.Simple;

import io.restassured.builder.MultiPartSpecBuilder;
import io.restassured.http.ContentType;
import io.restassured.specification.MultiPartSpecification;

/**
 * Testet the Daemon-Mode input , Methoden , Output Content-Type and the success case
 *
 * @author Roula Antoun
 * @author Andreas Penski
 */
public class CheckHandlerIT extends BaseIT {

    private static final String APPLICATION_XML = "application/xml";

    @Test
    public void simpleTest() {
        given().contentType(ContentType.XML).body(Paths.get(Simple.SIMPLE_VALID).toFile()).log().all().when().post("/").then()
                .statusCode(SC_OK);
    }

    @Test
    public void noInputTest() {
        given().body("").contentType(APPLICATION_XML).when().post("/").then().statusCode(HttpStatus.SC_BAD_REQUEST);
    }

    @Test
    public void testUnknown() throws IOException {
        given().contentType(APPLICATION_XML).body(Paths.get(Simple.UNKNOWN).toFile()).when().post("/").then().statusCode(SC_NOT_ACCEPTABLE);
    }

    private static byte[] toContent(final InputStream io) throws IOException {
        final byte[] bytes = IOUtils.toByteArray(io);
        if (bytes.length == 0) {
            System.out.println("nopen");
        }
        return bytes;
    }

    @Test
    public void xmlResultTest() throws IOException {
        try ( final InputStream io = Simple.SIMPLE_VALID.toURL().openStream() ) {
            given().body(toContent(io)).contentType(APPLICATION_XML + "; charset=UTF-8").when().post("/").then()
                    .contentType(APPLICATION_XML).and()
                    .statusCode(SC_OK);
        }
    }

    @Test
    public void testMultipart() throws IOException {
        try ( final InputStream io = Simple.SIMPLE_VALID.toURL().openStream() ) {
            final MultiPartSpecification spec = new MultiPartSpecBuilder(io).fileName("file").controlName("file").build();
            given().multiPart(spec).when().post("/").then().statusCode(HttpStatus.SC_BAD_REQUEST);
        }
    }

}
