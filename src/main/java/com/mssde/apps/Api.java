package com.mssde.apps;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.web.bind.annotation.*;

import static java.lang.Math.*;

import javax.sql.DataSource;
import java.util.Map;
import java.util.UUID;

@SpringBootApplication
public class Api {

    public static void main(String[] args) {
        if (args.length < 2) {
            System.out.println("Please provide listen address and port to listen as arguments.");
            return;
        }

        String listenTo = args[0];
        String port = args[1];
        System.setProperty("server.address", listenTo);
        System.setProperty("server.port", port);
        SpringApplication.run(Api.class, args);
    }

    @Bean
    public DataSource dataSource() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.sqlite.JDBC");
        dataSource.setUrl("jdbc:sqlite:dronesystem.db");
        return dataSource;
    }

    @Bean
    public JdbcTemplate jdbcTemplate(DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }

    @RestController
    public class ApiController {

        private final JdbcTemplate jdbcTemplate;

        public ApiController(JdbcTemplate jdbcTemplate) {
            this.jdbcTemplate = jdbcTemplate;
        }

        @PostMapping("/request")
        public ResponseEntity<Map<String, Object>> request(@RequestBody Map<String, Object> payload) {
            // Check existing request
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM requests WHERE origin_latlong = ? AND dest_latlong = ? AND curr_status NOT IN ('done', 'cancelled')",
                    new Object[] { payload.get("origin_latlong"), payload.get("dest_latlong") },
                    Integer.class);

            if (count != null && count > 0) {
                return new ResponseEntity<>(Map.of("error", "Another request with similar data already exists."),
                        HttpStatus.BAD_REQUEST);
            }

            UUID requestID = UUID.randomUUID();
            jdbcTemplate.update(
                    "INSERT INTO requests (request_id, origin_latlong, dest_latlong, weight, curr_status) VALUES (?, ?, ?, ?, ?)",
                    requestID, payload.get("origin_latlong"), payload.get("dest_latlong"), payload.get("weight"),
                    "pending");

            return new ResponseEntity<>(Map.of("request_id", requestID, "status", "pending"), HttpStatus.CREATED);
        }

        @PostMapping("/cancel")
        public ResponseEntity<Map<String, Object>> cancel(@RequestBody Map<String, Object> payload) {
            String requestID = (String) payload.get("request_id");

            int updated = jdbcTemplate.update(
                    "UPDATE requests SET curr_status = 'cancelling' WHERE request_id = ? AND (curr_status = 'pending' OR curr_status = 'on route' OR curr_status = 'assigned')",
                    requestID);

            if (updated > 0) {
                return new ResponseEntity<>(Map.of("request_id", requestID, "status", "cancelled"), HttpStatus.CREATED);
            } else {
                return new ResponseEntity<>(Map.of("error", "Request not found or cannot be cancelled."),
                        HttpStatus.BAD_REQUEST);
            }
        }

        @GetMapping("/status/{request_id}")
        public ResponseEntity<Map<String, Object>> status(@PathVariable String request_id) {
            Map<String, Object> result = jdbcTemplate.queryForMap("SELECT * FROM requests WHERE request_id = ?",
                    request_id);

            if (result == null || result.isEmpty()) {
                return new ResponseEntity<>(HttpStatus.NOT_FOUND);
            }

            if (result.get("curr_latlong") != null) {
                String[] origin = ((String) result.get("origin_latlong")).split(",");
                String[] curr = ((String) result.get("curr_latlong")).split(",");
                double distance = calcDistance(Double.parseDouble(origin[0]), Double.parseDouble(origin[1]),
                        Double.parseDouble(curr[0]), Double.parseDouble(curr[1]));

                result.put("distance_to_destination", distance);
            }

            return new ResponseEntity<>(result, HttpStatus.OK);
        }

        /**
         * Calculate distance between two points in latitude and longitude.
         * Haversine formula
         */
        public double calcDistance(double lat1, double lon1, double lat2, double lon2) {
            final int R = 6371; // Radius of the earth in km
            double dLat = toRadians(lat2 - lat1);
            double dLon = toRadians(lon2 - lon1);
            double a = sin(dLat / 2) * sin(dLat / 2) +
                    cos(toRadians(lat1)) * cos(toRadians(lat2)) *
                            sin(dLon / 2) * sin(dLon / 2);
            double c = 2 * atan2(sqrt(a), sqrt(1 - a));
            double distance = R * c; // Convert to meters
            return distance * 1000;
        }
    }
}
