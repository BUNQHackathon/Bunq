# Builds and pushes the Spring Boot image to ECR using Maven Jib.
# Jib builds a container image without a Docker daemon — no Docker Desktop needed.
# Triggered by a hash of src/ + pom.xml so re-apply rebuilds on code change.

resource "null_resource" "jib_build" {
  triggers = {
    src_hash = sha1(join("", [
      for f in sort(fileset("${path.module}/../src", "**")) :
      filesha1("${path.module}/../src/${f}")
    ]))
    pom_hash  = filesha1("${path.module}/../pom.xml")
    image_tag = var.image_tag
    ecr_url   = aws_ecr_repository.backend.repository_url
  }

  provisioner "local-exec" {
    interpreter = ["powershell", "-NoProfile", "-Command"]
    working_dir = "${path.module}/.."
    command     = <<-EOT
      $ErrorActionPreference = 'Stop'
      $ecrImage = '${aws_ecr_repository.backend.repository_url}:${var.image_tag}'
      $pw = aws ecr get-login-password --region ${var.region} --profile ${var.aws_profile}
      .\mvnw.cmd -B -DskipTests compile jib:build "-Djib.to.image=$ecrImage" "-Djib.to.auth.username=AWS" "-Djib.to.auth.password=$pw"
    EOT
  }

  depends_on = [aws_ecr_repository.backend]
}
