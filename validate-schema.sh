#!/usr/bin/bash

xmllint --schema jflatpak-manifest/src/main/resources/schema/flatpak-manifest.xsd test-manifest.xml --noout
xmllint --schema jflatpak-manifest/src/main/resources/schema/flatpak-manifest.xsd test-module.xml --noout
