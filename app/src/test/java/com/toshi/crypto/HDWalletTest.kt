package com.toshi.crypto

import android.content.Context
import com.toshi.testSharedPrefs.TestWalletPrefs
import org.hamcrest.CoreMatchers.`is`
import org.hamcrest.MatcherAssert.assertThat
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito

class HDWalletTest {

    private val expectedMasterSeed = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
    // Path `m/0'/1/0/0
    private val expectedOwnerAddress = "0xa391af6a522436f335b7c6486640153641847ea2"

    // Path `m/44'/60'/0'/0'
    private val expectedPaymentAddress = "0x9858effd232b4033e47d90003d41ec34ecaeda94"

    // Mocks
    private lateinit var walletPrefs: TestWalletPrefs
    private lateinit var context: Context

    @Before
    fun setup() {
        walletPrefs = TestWalletPrefs()
        walletPrefs.setMasterSeed(expectedMasterSeed)
        context = Mockito.mock(Context::class.java)
    }

    @Test
    fun walletCreatedFromSeedUsesThatSeed() {
        val wallet = HdWalletBuilder(walletPrefs, context)
                .getExistingWallet()
                .toBlocking()
                .value()
        assertThat(wallet.masterSeed, `is`(expectedMasterSeed))
    }

    @Test
    fun walletCreatedFromSeedDerivesCorrectOwnerAddress() {
        val wallet = HdWalletBuilder(walletPrefs, context)
                .getExistingWallet()
                .toBlocking()
                .value()
        assertThat(wallet.ownerAddress, `is`(expectedOwnerAddress))
    }

    @Test
    fun walletCreatedFromSeedDerivesCorrectPaymentAddress() {
        val wallet = HdWalletBuilder(walletPrefs, context)
                .getExistingWallet()
                .toBlocking()
                .value()
        assertThat(wallet.paymentAddress, `is`(expectedPaymentAddress))
    }

    @Test
    fun walletAddressesDerivedFromSeed() {
        val hdwallet = HdWalletBuilder(walletPrefs, context)
                .buildFromMasterSeed(expectedMasterSeed)
                .toBlocking()
                .value()

        val expected = listOf(
                "0x9858effd232b4033e47d90003d41ec34ecaeda94",
                "0x6fac4d18c912343bf86fa7049364dd4e424ab9c0",
                "0xb6716976a3ebe8d39aceb04372f22ff8e6802d7a",
                "0xf3f50213c1d2e255e4b2bad430f8a38eef8d718e",
                "0x51ca8ff9f1c0a99f88e86b8112ea3237f55374ca",
                "0xa40cfbfc8534ffc84e20a7d8bbc3729b26a35f6f",
                "0xb191a13bfe648b61002f2e2135867015b71816a6",
                "0x593814d3309e2df31d112824f0bb5aa7cb0d7d47",
                "0xb14c391e2bf19e5a26941617ab546fa620a4f163",
                "0x4c1c56443abfe6dd33de31daaf0a6e929dbc4971"
        )

        for (index in expected.indices) {
            hdwallet.changeWallet(index)
            assertThat(hdwallet.paymentAddress, `is`(expected[index]))
        }
    }
}