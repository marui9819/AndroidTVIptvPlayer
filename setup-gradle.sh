#!/bin/bash

echo "Setting up Gradle wrapper..."

# Create gradle wrapper directory if it doesn't exist
mkdir -p gradle/wrapper

# Download gradle-wrapper.jar if it doesn't exist
if [ ! -f "gradle/wrapper/gradle-wrapper.jar" ]; then
    echo "Downloading gradle-wrapper.jar..."
    curl -L -o gradle/wrapper/gradle-wrapper.jar https://github.com/gradle/gradle/raw/v8.4.0/gradle/wrapper/gradle-wrapper.jar
fi

# Make gradlew executable
chmod +x gradlew

echo "Gradle wrapper setup complete!"
echo "You can now run: ./gradlew build"