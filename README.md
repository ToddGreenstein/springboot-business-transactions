# springboot-business-transactions
Java Spring Boot REST API front end for a Couchbase POC
## REQUIREMENTS
- **Clone this repo**   
- **Provision a couchbase instance**   
Point the src/main/resources/application.properties "hostname" to the couchbase instance
- **Build the application**   
./gradlew run

## Usage
Binds to http://localhost:8080 by default.

For mappings, try http://localhost:8080/mappings

For Reference of available endpoints and curl examples, refer to the PocApplication class

Example: Seed a Company, Tier, Application and Business Properties  
curl 'http://localhost:8080/seedEntities'
 
