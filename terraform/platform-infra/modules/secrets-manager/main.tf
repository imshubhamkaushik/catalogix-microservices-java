data "aws_region" "current" {}

# Force-delete any same-named secret that is pending deletion.
# Secrets Manager holds deleted secrets for up to 30 days by default.
# Without this, terraform apply fails with InvalidRequestException when
# re-applying after a destroy if the previous deletion used a recovery window.

# Force-delete-on-recreate workaround removed.
#
# Previously this used a terraform_data + local-exec provisioner that shelled
# out to the `aws` CLI directly. That re-introduces the exact hidden runtime
# dependency this codebase explicitly engineered around elsewhere — see the
# comment on the aws-auth ConfigMap in env/dev/main.tf, which documents why
# local-exec was removed from there ("broke on clean CI runners").
#
# It's also unnecessary going forward: aws_secretsmanager_secret.this below
# sets recovery_window_in_days = 0, so AWS deletes the secret immediately
# (no recovery window) whenever Terraform destroys it. There is nothing left
# in a "pending deletion" state for a future apply to collide with.

resource "aws_secretsmanager_secret" "this" {
  name        = var.secret_name
  description = "Application database secrets for ${var.project_name}"

  recovery_window_in_days = 0 # Disable deletion protection, fine for dev not for production

  # depends_on = [terraform_data.force_delete_pending_secret]
}

resource "aws_secretsmanager_secret_version" "value" {
  secret_id     = aws_secretsmanager_secret.this.id
  secret_string = jsonencode(var.secret_values)
}