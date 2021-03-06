FROM azul/zulu-openjdk:7u65

RUN apt-get update
RUN apt-get upgrade -y

# dependencies for building zeromq
RUN DEBIAN_FRONTEND=noninteractive apt-get install -y -q build-essential 
RUN DEBIAN_FRONTEND=noninteractive apt-get install -y -q autoconf automake libtool
RUN DEBIAN_FRONTEND=noninteractive apt-get install -y -q uuid-dev

# curl
RUN DEBIAN_FRONTEND=noninteractive apt-get install -y -q curl 

# build & install zeromq
RUN curl "http://download.zeromq.org/zeromq-3.2.4.tar.gz" | tar xzf -
RUN cd zeromq-3.2.4 && ./configure
RUN cd zeromq-3.2.4 && make
RUN cd zeromq-3.2.4 && make install
RUN ldconfig
RUN rm -Rf zeromq-3.2.4

# dependencies for building jzmq
RUN DEBIAN_FRONTEND=noninteractive apt-get install -y -q pkg-config
RUN DEBIAN_FRONTEND=noninteractive apt-get install -y -q unzip 

# build & install jzmq
RUN curl --location https://github.com/zeromq/jzmq/archive/v3.1.0.zip -o jzmq.zip
RUN unzip jzmq.zip
RUN cd jzmq-3.1.0 && ./autogen.sh
RUN cd jzmq-3.1.0 && ./configure
RUN cd jzmq-3.1.0 && make
RUN cd jzmq-3.1.0 && make install

# create patavi user
RUN useradd --create-home --home /var/lib/patavi patavi

# download & install leiningen
RUN su patavi -c 'mkdir /var/lib/patavi/bin'
RUN su patavi -c 'curl -L "https://raw.github.com/technomancy/leiningen/stable/bin/lein" -o /var/lib/patavi/bin/lein' 
RUN chmod +x /var/lib/patavi/bin/lein
RUN su - patavi -c 'lein upgrade'
