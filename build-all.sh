#! /bin/bash

set -eo pipefail

rm -rf target
rm -f pom.xml

for file in `find . -name '.cpcache'`; do
    rm -rf "$file"
done

script_directory=`dirname "${BASH_SOURCE[0]}"`
cd "$script_directory"
script_directory=`pwd`

version="$1"

if [ ! "$version" ]; then
    echo "Usage: ./build-all.sh [version]"
    exit -1
fi

build_jar () {
    clojure -X:jar :version "\"$version\""
}

build_all () {
    echo "Build toucan2-core"
    cd "$script_directory"/toucan2-core
    rm -f pom.xml
    clojure -Spom
    build_jar

    echo "Build toucan2-honeysql"
    cd "$script_directory"/toucan2-honeysql
    rm -f pom.xml
    clojure -Sdeps "{:deps {com.camsaul/toucan2-core {:mvn/version \"$version\"}}}" -Spom
    build_jar

    echo "Build toucan2-jdbc"
    cd "$script_directory"/toucan2-jdbc
    rm -f pom.xml
    clojure -Sdeps "{:deps {com.camsaul/toucan2-core {:mvn/version \"$version\"}}}" -Spom
    build_jar

    echo "Build toucan2"
    cd "$script_directory"/toucan2
    rm -f pom.xml
    clojure -Sdeps "{:deps {com.camsaul/toucan2-core {:mvn/version \"$version\"}
                            com.camsaul/toucan2-honeysql {:mvn/version \"$version\"}
                            com.camsaul/toucan2-jdbc {:mvn/version \"$version\"}}}" \
            -Spom
    build_jar
}

build_all