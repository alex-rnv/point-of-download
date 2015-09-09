# Wget-server

## Description
Named after popular shell tool, it is not actually the same thing. Wget-server is a simple proxy, which aims to download big files from remote servers to local network, and then provide them for internal components. Lets say, there are several components, which require the same big file, requests are rather frequent, sometimes simultaneous. Instead of sending several http get request to remote host, components send requests to proxy, which downloads file only once and caches it. Assuming that remote network is slow and subject to internal errors, wget-server will do retries as configured, and guarantee that either all its clients get the result or no one gets it. In common case, request from first http component is slower, while next requests are much faster, as file is distributed among local network (assuming wget-server is in this network). There are some sketches [here](https://github.com/alex-rnv/wget-server/wiki/Diagrams).    

### Rationale
Actually, the task could be solved with load balancers like nginx, httpd, or [varnish](https://www.varnish-cache.org/) - special caching reverse proxy for cases like ours. The problems are, all those solutions designed to work with web pages, which are much smaller than our files, and should be cached with big care, as they subject to alteration and carry specific http headers, while our files remain the same. Another problem is that those servers are harder to install and maintain.
Also, we may have cases when multiple components require the same file. Only one request is sent to downstream, but all components await the response and will be notified asynchronously. 

## Design details    
Instead of using http get request directly, request is sent to wget-server, and requested url is passed as http header field.

## Usage    
Wget-server is a standalone microservice implemented as [Vert.x 3](http://vertx.io/vertx2/) component. Generally, there is no need to include library in your dependencies, though it is possible.    
### Simple Java process    
*Note:* vert.x runner should be in your system PATH, please follow [vert.x installation procedure](http://vertx.io/vertx2/install.html).    
```wget https://bintray.com/artifact/download/alex-rnv-ru/maven/com/alexrnv/wget-server/1.0.1/wget-server-1.0.1.jar        
java -jar wget-server-1.0.1.jar -conf <config_file>```    
Configuration file is described further.    

### Vertx Service
TBD    

### Maven
First, update your *settings.xml* with    
```xml
<?xml version='1.0' encoding='UTF-8'?>
<settings xsi:schemaLocation='http://maven.apache.org/SETTINGS/1.0.0 http://maven.apache.org/xsd/settings-1.0.0.xsd' xmlns='http://maven.apache.org/SETTINGS/1.0.0' xmlns:xsi='http://www.w3.org/2001/XMLSchema-instance'>
<profiles>
	<profile>
		<repositories>
			<repository>
				<snapshots>
					<enabled>false</enabled>
				</snapshots>
				<id>bintray-alex-rnv-ru-maven</id>
				<name>bintray</name>
				<url>http://dl.bintray.com/alex-rnv-ru/maven</url>
			</repository>
		</repositories>
		<pluginRepositories>
			<pluginRepository>
				<snapshots>
					<enabled>false</enabled>
				</snapshots>
				<id>bintray-alex-rnv-ru-maven</id>
				<name>bintray-plugins</name>
				<url>http://dl.bintray.com/alex-rnv-ru/maven</url>
			</pluginRepository>
		</pluginRepositories>
		<id>bintray</id>
	</profile>
</profiles>
<activeProfiles>
	<activeProfile>bintray</activeProfile>
</activeProfiles>
</settings>
```
Add dependency to your pom.xml:    
```xml
<dependency>
        <groupId>com.alexrnv</groupId>
        <artifactId>wget-server</artifactId>
        <version>1.0.1</version>
</dependency>
```
### Gradle
Add repository to your build.gradle    
```
repositories {
    maven {
        url  "http://dl.bintray.com/alex-rnv-ru/maven" 
    }
}
```
Add dependency    
```
compile(group: 'com.alexrnv', name: 'wget-server', version: '1.0.1', ext: 'jar')
```

## Configuration
Service will not work without configuration, which could be provided as json object internally during vertx verticle deployment, or via *-conf* command line parameter. Configuration format is JSON(Vert.x standard). Here is sample configuration file:    
```json
{
  "requestTimeoutMs": 180000,
  "downloadHeader": "Referer",
  "resultHeader": "Location",
  "podTopic": "downloader",
  "cacheDir": "%t",
  "ttlMin": 120,
  "upstream": {
    "host": "localhost",
    "port": 8070
  },
  "retry": {
    "numRetries": 2,
    "delayMs": 1000
  }
}
```
Parameters:    
* **requestTimeoutMs** - timeout for downstream server response. If reached, 500 error is returned to requester.    
* **downloadHeader** - http header name to pass url of the file to download. 
* **resultHeader** - header name to exchange cached file name between wget-server components.
* **podTopic** - unique topic name for wget-server components communication.
* **cacheDir** - directory for local cached files. %t is OS specific temp directory.
* **ttlMin** - cached values life time in minutes.
* **upstream** - host and port for wget-server to listen http requests.
* **retry** - fixed delay retry policy parameters: number of retries and delay between retries in milliseconds.

## TODO
* SSL
* TTL for individual request
* Better retry policies (exponential back-off, etc.)
* Concise Logging (currently it is JUL, log files in /tmp folder)

