package com.queryskiff.resource

import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.CoreMatchers.startsWith
import org.junit.jupiter.api.Test

/**
 * HEL-95: boot smoke over the Quarkus canary — the MinIO-free contract slices
 * (health, 400 shapes, quirks). The full 105-requirement dual-target Python
 * contract suite against a running canary with real MinIO is the migration's
 * definition of done; this pins the HTTP wiring boots and speaks the shapes.
 */
@QuarkusTest
class ApiSmokeTest {

    @Test
    fun `health endpoints`() {
        given().get("/queryskiff/api/health").then().statusCode(200)
            .body("ok", equalTo(true))
        given().get("/queryskiff/health").then().statusCode(200)
            .body("ok", equalTo(true))
    }

    @Test
    fun `missing dataset_id is 400 with the evolved contract message`() {
        given().contentType("application/json").body("""{"sql": "SELECT 1"}""")
            .post("/queryskiff/api/queries").then().statusCode(400)
            .body("detail", equalTo("dataset_id or datasets required"))
    }

    @Test
    fun `forged workspace id is 400 browser-safe`() {
        given().contentType("application/json")
            .body("""{"datasets": [{"dataset_id": "Zm9yZ2VkAGJhZC5wYXJxdWV0", "alias": "x"}],
                      "sql": "SELECT * FROM x"}""")
            .post("/queryskiff/api/queries").then().statusCode(400)
    }

    @Test
    fun `invalid alias is 400`() {
        given().contentType("application/json")
            .body("""{"datasets": [{"dataset_id": "abc", "alias": "1bad"}], "sql": "SELECT 1"}""")
            .post("/queryskiff/api/queries").then().statusCode(400)
            .body("detail", startsWith("invalid alias"))
    }

    @Test
    fun `unknown query status is 404 but DELETE is the 200-false quirk`() {
        given().get("/queryskiff/api/queries/nope").then().statusCode(404)
        given().delete("/queryskiff/api/queries/nope").then().statusCode(200)
            .body("cancelled", equalTo(false))
    }

    @Test
    fun `spa fallback serves index when bundled else honest 404 json`() {
        // the frontend bundle is staged (not committed) into META-INF/resources
        // for remote contract runs — assert whichever state this build is in.
        val bundled = javaClass
            .getResource("/META-INF/resources/queryskiff/index.html") != null
        if (bundled) {
            given().get("/queryskiff/some/client/route").then().statusCode(200)
                .contentType("text/html")
        } else {
            given().get("/queryskiff/some/client/route").then().statusCode(404)
                .body("error", equalTo("frontend not built"))
        }
    }
}
