docker-ip() {
  sudo docker inspect --format '{{ .NetworkSettings.IPAddress }}' "$@"
}

onos-app `docker-ip onos1` install target/dices-app-1.0-SNAPSHOT.oar
