rm target/Consoles.zip
mvn clean
mvn package -Pbukkit
mvn package -Pbungee
cd target
cd bukkit-final/
for FILENAME in *; do mv $FILENAME consoles.jar; done
zip ../Consoles.zip consoles.jar
cd ../bungee-final/
for FILENAME in *; do mv $FILENAME bungee-consoles.jar; done
zip ../Consoles.zip bungee-consoles.jar
cd ../..