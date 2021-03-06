#!/usr/bin/env bash
#
# Copyright (c) 2013-2016 Commonwealth Computer Research, Inc.
# All rights reserved. This program and the accompanying materials
# are made available under the terms of the Apache License, Version 2.0 which
# accompanies this distribution and is available at
# http://www.opensource.org/licenses/apache2.0.php.
#

# Check environment variables before running anything, warn user on issues:
if [[ (-z "$GEOMESA_HOME") ]]; then
    echo "Error: GEOMESA_HOME environmental variable not found...install geomesa or define GEOMESA_HOME and try again"
    exit
else
    imageio_version='1.1.13'
    imageio_gdal_bindings_version='1.9.2'
    partial_url='webdav/geotools/it/geosolutions/imageio-ext'
    osgeo_url='http://download.osgeo.org'
    imageio_gdal_bindings_jarname="imageio-ext-gdal-bindings-${imageio_gdal_bindings_version}.jar"
    imageio_gdal_bindings_url="${osgeo_url}/${partial_url}/imageio-ext-gdal-bindings/${imageio_gdal_bindings_version}/${imageio_gdal_bindings_jarname}"

    imageio_jars=(\
        imageio-ext-gdalarcbinarygrid \
        imageio-ext-gdalframework \
        imageio-ext-gdaldted \
        imageio-ext-gdalecw \
        imageio-ext-gdalecwjp2 \
        imageio-ext-gdalehdr \
        imageio-ext-gdalenvihdr \
        imageio-ext-gdalerdasimg \
        imageio-ext-gdalframework \
        imageio-ext-gdalidrisi \
        imageio-ext-gdalkakadujp2 \
        imageio-ext-gdalmrsid \
        imageio-ext-gdalmrsidjp2 \
        imageio-ext-gdalnitf \
        imageio-ext-gdalrpftoc \
        imageio-ext-geocore \
        imageio-ext-imagereadmt \
        imageio-ext-streams \
        imageio-ext-tiff \
        imageio-ext-utilities\
    )

    read -r -p "Imageio-ext 1.1.13 is GNU Lesser General Public licensed and is not distributed with GeoMesa...are you sure you want to install it from $url_gtimageioextgdal ? [Y/n]" confirm
    confirm=${confirm,,} #lowercasing
    if [[ $confirm =~ ^(yes|y) ]]; then
        echo "Trying to install ${#imageio_jars[@]} ImageIo EXT Jars from ${osgeo_url}/webdav/geotools/... to $GEOMESA_HOME"
        for x in "${imageio_jars[@]}"; do
            thisJar="${x}-${imageio_version}.jar"
            thisURL="${osgeo_url}/webdav/geotools/it/geosolutions/imageio-ext/${x}/${imageio_version}/${thisJar}"
            thisDownloadPath="$GEOMESA_HOME/lib/common/$thisJar"
            wget -O ${thisDownloadPath} ${thisURL} \
                && chmod 0644 "${thisDownloadPath}" \
                && echo "Successfully installed ${thisJar} to ${GEOMESA_HOME}";
        done
        imageio_gdal_bindings_downloadpath="$GEOMESA_HOME/lib/common/$imageio_gdal_bindings_jarname"
        wget -O ${imageio_gdal_bindings_downloadpath} ${imageio_gdal_bindings_url} \
            && chmod 0644 "${imageio_gdal_bindings_downloadpath}" \
            && echo "Successfully installed ${imageio_gdal_bindings_jarname} to ${GEOMESA_HOME}"
    else
        echo "Cancelled installation of Imageio-ext 1.1.13"
    fi
fi
