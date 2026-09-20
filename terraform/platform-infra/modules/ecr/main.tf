data "aws_caller_identity" "current" {}
data "aws_region" "current" {}

resource "aws_ecr_repository" "repos" {
  for_each = toset(var.repositories)

  name = each.value

  image_tag_mutability = "IMMUTABLE"

  # force_delete = true allows Terraform to delete the repository even if it contains images. 
  # This is convenient for development, but be cautious using it in production as it can lead to data loss if the repository is accidentally destroyed.
  # In production, consider setting force_delete to false and implementing a lifecycle policy that expires old images, 
  # or manually clean up images before destroying the repository.
  force_delete = true

  image_scanning_configuration {
    scan_on_push = true
  }

  encryption_configuration {
    encryption_type = "AES256"
  }

  # This module is called once, from env/dev — staging deploys the same
  # images from these same repos with no ECR module of its own (see
  # env/staging/main.tf's comment on why). `terraform destroy` against
  # dev's state would delete repos staging is still actively pulling
  # from, with no warning from Terraform itself since staging's state has
  # no record of depending on them. prevent_destroy turns that into a
  # hard error instead of a silent cross-environment outage; remove it
  # deliberately (and confirm staging no longer needs these repos first)
  # if this environment is ever actually being torn down for good.
  lifecycle {
    prevent_destroy = true
  }
}

resource "aws_ecr_lifecycle_policy" "repos" {
  for_each   = toset(var.repositories)
  repository = aws_ecr_repository.repos[each.key].name

  policy = jsonencode({
    rules = [
      {
        rulePriority = 1
        description  = "Keep last 5 tagged images"
        selection = {
          tagStatus      = "tagged"
          tagPatternList = ["*"]
          countType      = "imageCountMoreThan"
          countNumber    = 5
        }
        action = { type = "expire" }
      },
      {
        rulePriority = 2
        description  = "Expire untagged images after 1 day"
        selection = {
          tagStatus   = "untagged"
          countType   = "sinceImagePushed"
          countUnit   = "days"
          countNumber = 1
        }
        action = { type = "expire" }
      }
    ]
  })
}