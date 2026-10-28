package studio.koeda.norrklang.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import studio.koeda.norrklang.data.mtls.MtlsManager.Status
import studio.koeda.norrklang.data.mtls.MtlsManager.UploadMethod
import studio.koeda.norrklang.ui.R
import studio.koeda.norrklang.ui.components.BackButton
import studio.koeda.norrklang.ui.components.QrCode
import studio.koeda.norrklang.ui.theme.LocalFormDimens

/**
 * The mTLS workflow page: generate the keypair, present its public key (QR /
 * copy / upload endpoint), then download the server-signed certificate. The
 * private key never leaves the device.
 */
@Composable
fun MtlsPage(
    viewModel: MtlsViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val dimens = LocalFormDimens.current
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = dimens.screenPadding, vertical = dimens.itemSpacing),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BackButton(onBack)
            Text(
                text = stringResource(R.string.mtls_title),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        Spacer(Modifier.height(dimens.itemSpacing))
        HorizontalDivider()
        Text(
            text = stringResource(R.string.mtls_description),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(dimens.itemSpacing))

        MtlsWorkflowSection(viewModel, includeMessage = false)

        Spacer(Modifier.height(dimens.itemSpacing * 2))
        HorizontalDivider()
        OutlinedButton(
            onClick = viewModel::resetIdentity,
            enabled = !busy,
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .widthIn(min = 200.dp)
                .heightIn(min = dimens.controlMinHeight),
        ) {
            Text(text = stringResource(R.string.mtls_reset), fontWeight = FontWeight.Bold)
        }

        message?.let {
            Spacer(Modifier.height(dimens.itemSpacing * 2))
            Text(
                text = it,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
            )
        }
        Spacer(Modifier.height(dimens.itemSpacing))
    }
}

/**
 * The keypair/certificate/root-CA workflow, shared by the settings page and
 * the sign-in screen (an mTLS server must be set up BEFORE the first
 * sign-in, so the page cannot be the only door to this).
 */
@Composable
fun MtlsWorkflowSection(
    viewModel: MtlsViewModel,
    includeMessage: Boolean = true,
) {
    val status by viewModel.status.collectAsStateWithLifecycle()
    val uploadUrl by viewModel.uploadUrl.collectAsStateWithLifecycle()
    val downloadUrl by viewModel.downloadUrl.collectAsStateWithLifecycle()
    val uploadMethod by viewModel.uploadMethod.collectAsStateWithLifecycle()
    val rootCa by viewModel.rootCa.collectAsStateWithLifecycle()
    val rootCaUrl by viewModel.rootCaUrl.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val log by viewModel.log.collectAsStateWithLifecycle()
    val dimens = LocalFormDimens.current

    val currentStatus = status
    val csrPem = when (currentStatus) {
        is Status.KeyGenerated -> currentStatus.csrPem
        is Status.Ready -> currentStatus.csrPem
        Status.None -> null
    }

    if (csrPem == null) {
        Text(
            text = stringResource(R.string.mtls_status_none),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(dimens.itemSpacing))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
        ) {
            Button(
                onClick = viewModel::generateKeyPair,
                enabled = !busy,
                modifier = Modifier
                    .widthIn(min = 200.dp)
                    .heightIn(min = dimens.controlMinHeight),
            ) {
                Text(text = stringResource(R.string.mtls_generate), fontWeight = FontWeight.Bold)
            }
        }
    } else {
        Text(
            text = stringResource(
                if (status is Status.Ready) {
                    R.string.mtls_status_ready
                } else {
                    R.string.mtls_status_key
                },
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(dimens.itemSpacing))
        Row(verticalAlignment = Alignment.CenterVertically) {
            QrCode(
                content = csrPem,
                contentDescription = stringResource(R.string.mtls_qr),
                modifier = Modifier.size(dimens.controlMinHeight * 3),
            )
            Column(modifier = Modifier.padding(start = dimens.itemSpacing)) {
                Text(
                    text = stringResource(R.string.mtls_public_key),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Text(
                    text = stringResource(R.string.mtls_public_key_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(dimens.itemSpacing))
                OutlinedButton(
                    onClick = viewModel::copyCsr,
                    enabled = !busy,
                    modifier = Modifier.heightIn(min = dimens.controlMinHeight),
                ) {
                    Text(text = stringResource(R.string.mtls_copy_public_key))
                }
            }
        }
        Spacer(Modifier.height(dimens.itemSpacing * 2))
        HorizontalDivider()
        Text(
            text = stringResource(R.string.mtls_upload),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(dimens.itemSpacing))
        OutlinedTextField(
            value = uploadUrl,
            onValueChange = viewModel::setUploadUrl,
            label = { Text(stringResource(R.string.mtls_upload_url)) },
            placeholder = { Text(stringResource(R.string.mtls_upload_url_placeholder)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(dimens.itemSpacing))
        Row(verticalAlignment = Alignment.CenterVertically) {
            MethodButton(
                label = "GET",
                selected = uploadMethod == UploadMethod.GET,
                onClick = { viewModel.setUploadMethod(UploadMethod.GET) },
                enabled = !busy,
                minHeight = dimens.controlMinHeight,
            )
            MethodButton(
                label = "POST",
                selected = uploadMethod == UploadMethod.POST,
                onClick = { viewModel.setUploadMethod(UploadMethod.POST) },
                enabled = !busy,
                minHeight = dimens.controlMinHeight,
                modifier = Modifier.padding(start = dimens.itemSpacing),
            )
            Spacer(Modifier.weight(1f))
            Button(
                onClick = viewModel::uploadCsr,
                enabled = !busy,
                modifier = Modifier.heightIn(min = dimens.controlMinHeight),
            ) {
                Text(text = stringResource(R.string.mtls_upload_button), fontWeight = FontWeight.Bold)
            }
        }
        Spacer(Modifier.height(dimens.itemSpacing * 2))
        HorizontalDivider()
        Text(
            text = stringResource(R.string.mtls_download),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(dimens.itemSpacing))
        OutlinedTextField(
            value = downloadUrl,
            onValueChange = viewModel::setDownloadUrl,
            label = { Text(stringResource(R.string.mtls_download_url)) },
            placeholder = { Text(stringResource(R.string.mtls_download_url_placeholder)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(dimens.itemSpacing))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
        ) {
            Button(
                onClick = viewModel::downloadCertificate,
                enabled = !busy,
                modifier = Modifier
                    .widthIn(min = 200.dp)
                    .heightIn(min = dimens.controlMinHeight),
            ) {
                Text(text = stringResource(R.string.mtls_download_button), fontWeight = FontWeight.Bold)
            }
        }
    }

    // Trust the server, not only be trusted by it: a self-signed server
    // chain is rejected by the platform trust store without this — and it
    // is useful before any keypair exists.
    Spacer(Modifier.height(dimens.itemSpacing * 2))
    HorizontalDivider()
    Text(
        text = stringResource(R.string.mtls_root_ca_title),
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onBackground,
    )
    Spacer(Modifier.height(dimens.itemSpacing))
    Text(
        text = stringResource(R.string.mtls_root_ca_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(dimens.itemSpacing))
    OutlinedTextField(
        value = rootCaUrl,
        onValueChange = viewModel::setRootCaUrl,
        label = { Text(stringResource(R.string.mtls_root_ca_url)) },
        placeholder = { Text(stringResource(R.string.mtls_root_ca_url_placeholder)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(dimens.itemSpacing))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Button(
            onClick = viewModel::downloadRootCa,
            enabled = !busy,
            modifier = Modifier.heightIn(min = dimens.controlMinHeight),
        ) {
            Text(text = stringResource(R.string.mtls_root_ca_download), fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.weight(1f))
        rootCa?.let { ca ->
            Text(
                text = stringResource(R.string.mtls_root_ca_trusting, ca.subjectX500Principal.name),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(
                onClick = viewModel::removeRootCa,
                enabled = !busy,
                modifier = Modifier
                    .padding(start = dimens.itemSpacing)
                    .heightIn(min = dimens.controlMinHeight),
            ) {
                Text(text = stringResource(R.string.mtls_root_ca_remove))
            }
        }
    }

    if (includeMessage) {
        message?.let {
            Spacer(Modifier.height(dimens.itemSpacing * 2))
            Text(
                text = it,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
            )
        }
    }

    if (log.isNotEmpty()) {
        Spacer(Modifier.height(dimens.itemSpacing * 2))
        HorizontalDivider()
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.mtls_log_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(Modifier.weight(1f))
            OutlinedButton(
                onClick = viewModel::clearLog,
                modifier = Modifier.heightIn(min = dimens.controlMinHeight),
            ) {
                Text(text = stringResource(R.string.mtls_log_clear))
            }
        }
        Spacer(Modifier.height(dimens.itemSpacing))
        Text(
            text = log,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** GET/POST choice for the upload endpoint; the filled button is the active one. */
@Composable
private fun MethodButton(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    enabled: Boolean,
    minHeight: androidx.compose.ui.unit.Dp,
    modifier: Modifier = Modifier,
) {
    if (selected) {
        Button(
            onClick = onClick,
            enabled = enabled,
            modifier = modifier.heightIn(min = minHeight),
        ) {
            Text(label, fontWeight = FontWeight.Bold)
        }
    } else {
        OutlinedButton(
            onClick = onClick,
            enabled = enabled,
            modifier = modifier.heightIn(min = minHeight),
        ) {
            Text(label)
        }
    }
}
