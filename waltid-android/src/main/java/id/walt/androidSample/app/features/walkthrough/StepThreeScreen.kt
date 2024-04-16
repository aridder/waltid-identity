package id.walt.androidSample.app.features.walkthrough

import androidx.biometric.BiometricManager
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import androidx.navigation.compose.rememberNavController
import id.walt.androidSample.app.features.walkthrough.components.WalkthroughStep
import id.walt.androidSample.app.features.walkthrough.components.WaltPrimaryButton
import id.walt.androidSample.app.features.walkthrough.components.WaltSecondaryButton
import id.walt.androidSample.app.features.walkthrough.model.MethodOption
import id.walt.androidSample.app.util.authenticateWithBiometric
import id.walt.androidSample.theme.WaltIdAndroidSampleTheme


@Composable
fun StepThreeScreen(
    viewModel: WalkthroughViewModel,
    navController: NavController,
    modifier: Modifier = Modifier,
) {

    val methodOptions = viewModel.methodOptions
    val selectedMethodOption by viewModel.selectedMethod.collectAsStateWithLifecycle()
    val generatedDID by viewModel.did.collectAsStateWithLifecycle()

    val ctx = LocalContext.current

    val biometricManager = remember { BiometricManager.from(ctx) }
    val isBiometricsAvailable =
        biometricManager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.BIOMETRIC_WEAK) == BiometricManager.BIOMETRIC_SUCCESS

    WalkthroughStep(
        title = "Step 3 - Generate DID",
        description = "Generate a DID using the generated key pair.",
        modifier = modifier,
    ) {

        if (generatedDID != null) {
            Text(
                text = generatedDID.toString(),
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier
                    .padding(vertical = 8.dp)
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
            )
        } else {
            Spacer(modifier = Modifier.weight(1f))
        }

        MethodRadioGroup(
            selectedOption = selectedMethodOption,
            options = methodOptions,
            onOptionSelected = viewModel::onMethodOptionSelected,
        )

        Spacer(modifier = Modifier.height(16.dp))

        WaltSecondaryButton(
            text = "Generate DID",
            onClick = {
                authenticateWithBiometric(
                    context = ctx as FragmentActivity,
                    onAuthenticated = viewModel::onGenerateDIDClick,
                    onFailure = viewModel::onBiometricsAuthFailure,
                )
            },
            modifier = Modifier.fillMaxWidth()
        )
        WaltPrimaryButton(
            text = "Next Step",
            onClick = viewModel::onGoToStepFourClick,
            enabled = generatedDID != null,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun MethodRadioGroup(
    selectedOption: MethodOption,
    options: List<MethodOption>,
    onOptionSelected: (MethodOption) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.selectableGroup()) {
        options.forEach { option ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(40.dp)
                    .selectable(
                        selected = (option == selectedOption),
                        onClick = { onOptionSelected(option) },
                        role = Role.RadioButton
                    )
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(
                    selected = (option == selectedOption),
                    onClick = null
                )
                Text(
                    text = option.toString(),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(start = 16.dp)
                )
            }
        }
    }
}

@Preview
@Composable
private fun Preview() {
    WaltIdAndroidSampleTheme {
        StepThreeScreen(WalkthroughViewModel.Fake(), rememberNavController())
    }
}